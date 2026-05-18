package org.javaup.graphrag.controller;

import lombok.extern.slf4j.Slf4j;
import org.javaup.graphrag.dto.InstructorCoursesDto;
import org.javaup.graphrag.entity.Course;
import org.javaup.graphrag.entity.Instructor;
import org.javaup.graphrag.repository.CourseGraphRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 控制层
 * @author: 阿星不是程序员
 **/
@Slf4j
@RestController
@RequestMapping("/graph-rag")
public class GraphRagController {

    private final Neo4jTemplate neo4jTemplate;
    private final Neo4jClient neo4jClient;
    private final CourseGraphRepository courseRepo;
    private final ChatClient chatClient;

    public GraphRagController(Neo4jTemplate neo4jTemplate,
                              Neo4jClient neo4jClient,
                              CourseGraphRepository courseRepo,
                              ChatClient.Builder chatClientBuilder) {
        this.neo4jTemplate = neo4jTemplate;
        this.neo4jClient = neo4jClient;
        this.courseRepo = courseRepo;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 项目启动时自动初始化课程知识图谱数据
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initData() {
        log.info("开始初始化课程知识图谱数据...");

        // 先清空旧数据，避免重复
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();

        // 创建讲师节点
        neo4jTemplate.save(new Instructor("张老师"));
        neo4jTemplate.save(new Instructor("李老师"));
        neo4jTemplate.save(new Instructor("王老师"));

        // 创建课程节点
        neo4jTemplate.save(new Course("Spring Boot实战", 2023, "微服务"));
        neo4jTemplate.save(new Course("Spring Cloud微服务架构", 2023, "微服务"));
        neo4jTemplate.save(new Course("Docker容器技术", 2022, "DevOps"));
        neo4jTemplate.save(new Course("MyBatis从入门到精通", 2021, "持久层"));
        neo4jTemplate.save(new Course("Redis实战", 2022, "中间件"));
        neo4jTemplate.save(new Course("Kafka消息队列", 2023, "中间件"));
        neo4jTemplate.save(new Course("JVM调优实战", 2022, "性能优化"));

        // 创建讲授关系
        createTeachesRelation("张老师", "Spring Boot实战");
        createTeachesRelation("张老师", "Spring Cloud微服务架构");
        createTeachesRelation("张老师", "MyBatis从入门到精通");
        createTeachesRelation("李老师", "Spring Boot实战");
        createTeachesRelation("李老师", "Docker容器技术");
        createTeachesRelation("李老师", "Redis实战");
        createTeachesRelation("王老师", "JVM调优实战");
        createTeachesRelation("王老师", "Kafka消息队列");

        log.info("课程知识图谱初始化完成，共3位讲师、7门课程");
    }

    private void createTeachesRelation(String instructorName, String courseName) {
        neo4jClient.query("""
            MATCH (i:Instructor {name: $instructor})
            MATCH (c:Course {courseName: $course})
            MERGE (i)-[:TEACHES]->(c)
            """)
            .bind(instructorName).to("instructor")
            .bind(courseName).to("course")
            .run();
    }

    /**
     * Graph RAG问答接口
     */
    @GetMapping("/ask")
    public String ask(@RequestParam("question") String question) {
        // 1. 从问题中提取课程名
        String courseName = extractCourseName(question);
        log.info("提取到课程名：{}", courseName);

        // 2. 从知识图谱中查询关系数据
        String graphContext = queryGraphContext(courseName);
        log.info("图谱查询结果：{}", graphContext);

        if (graphContext.isBlank()) {
            return "抱歉，知识图谱中没有找到相关信息。";
        }

        // 3. 把图谱数据作为上下文，让大模型生成回答
        return chatClient.prompt()
                .system("""
                    你是一个技术课程知识助手。根据以下知识图谱数据回答用户的问题。
                    只基于提供的数据回答，不要编造信息。
                    用自然流畅的语言组织回答。

                    知识图谱数据：
                    """ + graphContext)
                .user(question)
                .call()
                .content();
    }

    private String queryGraphContext(String courseName) {
        List<InstructorCoursesDto> results = courseRepo.findOtherCoursesByInstructors(courseName);

        if (results.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("课程《%s》的讲师有：%s\n", courseName,
                results.stream().map(InstructorCoursesDto::instructor).collect(Collectors.joining("、"))));
        for (InstructorCoursesDto dto : results) {
            sb.append(String.format("其中 %s 还教了：%s\n",
                    dto.instructor(),
                    String.join("、", dto.otherCourses())));
        }
        return sb.toString();
    }

    private String extractCourseName(String question) {
        String raw = chatClient.prompt()
                .user("从以下问题中提取课程名称，只输出课程名本身，不要书名号、引号或任何多余的字符：" + question)
                .call()
                .content()
                .trim();
        // 去掉LLM可能加上的《》、""等包裹符号
        return raw.replaceAll("[\u300a\u300b\u201c\u201d\u2018\u2019\"']", "");
    }
}
