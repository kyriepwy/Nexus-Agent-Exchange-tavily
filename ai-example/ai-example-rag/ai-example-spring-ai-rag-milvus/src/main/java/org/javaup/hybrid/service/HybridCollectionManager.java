package org.javaup.hybrid.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import lombok.extern.slf4j.Slf4j;
import org.javaup.hybrid.config.HybridMilvusProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * Milvus 原生混合检索的 Collection 生命周期管理。
 * <p>
 * 这个类负责三件事：
 * 1. 创建带有 Dense + Sparse 双向量字段的 Collection（含 BM25 Function）
 * 2. 为两个向量字段分别建立索引
 * 3. 插入演示数据（Dense 向量通过 Spring AI EmbeddingModel 生成，Sparse 由 Milvus BM25 Function 自动生成）
 * <p>
 * 为什么不用 Spring AI 的 MilvusVectorStore 来管理 Collection？
 * 因为 Spring AI 的 VectorStore 抽象只支持单个 Dense 向量字段，
 * 它的 Schema 里没有 SparseFloatVector 字段和 BM25 Function 的概念。
 * 所以 Collection 的创建、索引、数据写入都得用 Milvus SDK V2 的原生 API。
 * 但 Embedding 向量的生成还是走 Spring AI 的 EmbeddingModel，不用手动调 HTTP。
 */
@Slf4j
@Service
public class HybridCollectionManager {

    /**
     * Milvus SDK V2 客户端，由 MilvusClientV2Config 注册。
     * 用它来操作 Collection 的创建、删除、加载，以及数据的插入。
     */
    private final MilvusClientV2 milvusClient;

    /**
     * Spring AI 的 Embedding 模型。
     * 插入数据时，调用它把文本转成 Dense 向量写入 text_dense 字段。
     * 这样就不用像 TinyRAG 那样手动拼 HTTP 请求调 SiliconFlow API 了。
     */
    private final EmbeddingModel embeddingModel;

    /** 混合检索的所有参数配置 */
    private final HybridMilvusProperties properties;

    public HybridCollectionManager(MilvusClientV2 milvusClient,
                                   EmbeddingModel embeddingModel,
                                   HybridMilvusProperties properties) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    /**
     * 检查 Hybrid Collection 是否已经存在。
     */
    public boolean collectionExists() {
        return Boolean.TRUE.equals(
                milvusClient.hasCollection(HasCollectionReq.builder()
                        .collectionName(properties.getCollectionName())
                        .build())
        );
    }

    /**
     * 删除已有的 Hybrid Collection。
     * <p>
     * 注意顺序：先 release（从内存中卸载）再 drop（从磁盘删除）。
     * 如果直接 drop 一个已经 load 到内存的 Collection，Milvus 可能会报错。
     */
    public void dropCollectionIfExists() {
        if (!collectionExists()) {
            return;
        }
        // 先从内存中卸载
        milvusClient.releaseCollection(ReleaseCollectionReq.builder()
                .collectionName(properties.getCollectionName())
                .build());
        // 再从磁盘删除
        milvusClient.dropCollection(DropCollectionReq.builder()
                .collectionName(properties.getCollectionName())
                .build());
        log.info("已删除 Hybrid Collection: {}", properties.getCollectionName());
    }

    /**
     * 创建支持混合检索的 Collection。
     * text_sparse 字段不需要手动写入，Milvus 的 BM25 Function 会在数据插入时自动生成。
     * <p>
     * 整个过程分三步：
     * 1) 定义 Schema（4 个字段 + 1 个 BM25 Function）
     * 2) 创建 Collection
     * 3) 为 Dense 和 Sparse 字段分别创建索引
     * <p>
     * Schema 包含 4 个字段：
     * - `id`（Int64，自增主键）Milvus 自动生成，不需要手动传入
     * - `text`（VarChar，开启 analyzer 供 BM25 分词）
     * - `text_dense`（FloatVector，存 Embedding 模型输出的稠密向量）
     * - `text_sparse`（SparseFloatVector，由 BM25 Function 从 text 字段自动生成，不需要手动写入）
     */
    public void createCollection() {
        // ========== 第一步：定义 Schema ==========
        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();

        // 主键字段：自增 ID
        // autoID=true 表示 Milvus 自动分配主键，插入数据时不需要传 id
        schema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(true)
                .build());

        // 文本字段：存储原始文本内容
        // enableAnalyzer=true 是关键！它告诉 Milvus 对这个字段开启文本分析器，
        // 这样 BM25 Function 才能对文本做分词，生成稀疏向量
        schema.addField(AddFieldReq.builder()
                .fieldName("text")
                .dataType(DataType.VarChar)
                .maxLength(properties.getMaxTextLength())
                .enableAnalyzer(true)
                .build());

        // 稠密向量字段：存放 Embedding 模型输出的浮点向量
        // dimension 必须和 Embedding 模型的输出维度一致（当前是 4096）
        // 插入数据时需要手动调用 EmbeddingModel 生成向量并填入这个字段
        schema.addField(AddFieldReq.builder()
                .fieldName("text_dense")
                .dataType(DataType.FloatVector)
                .dimension(properties.getDenseDimension())
                .build());

        // 稀疏向量字段：由 BM25 Function 自动填充
        // SparseFloatVector 不需要指定维度，因为稀疏向量的维度是动态的
        // 插入数据时不需要传这个字段的值，Milvus 会自动通过 BM25 Function 计算
        schema.addField(AddFieldReq.builder()
                .fieldName("text_sparse")
                .dataType(DataType.SparseFloatVector)
                .build());

        // 注册 BM25 Function：这是 Milvus 2.5+ 的新特性
        // 它告诉 Milvus：每当有数据写入 text 字段时，
        // 自动对文本做分词，计算 BM25 权重，生成稀疏向量写入 text_sparse 字段
        // 这样插入数据时只需要提供 text 和 text_dense，text_sparse 自动搞定
        schema.addFunction(CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25)
                .name("text_bm25_fn")
                .inputFieldNames(List.of("text"))
                .outputFieldNames(List.of("text_sparse"))
                .build());

        // ========== 第二步：创建 Collection ==========
        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(properties.getCollectionName())
                .collectionSchema(schema)
                .build());

        // ========== 第三步：创建索引 ==========
        // Dense 字段用 HNSW 索引 + COSINE 相似度
        // HNSW 是一种基于图的近似最近邻索引，检索速度快、召回率高
        // M=16：每个节点最多保留 16 条边，值越大索引越占内存但召回越好
        // efConstruction=256：建索引时的搜索宽度，值越大索引质量越高但建索引越慢
        IndexParam denseIndex = IndexParam.builder()
                .fieldName("text_dense")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of(
                        "M", properties.getDenseIndexM(),
                        "efConstruction", properties.getDenseIndexEfConstruction()
                ))
                .build();

        // Sparse 字段用 AUTOINDEX + BM25 度量
        // AUTOINDEX 让 Milvus 自动选择最合适的稀疏索引类型
        // BM25 度量类型表示用 BM25 算法来计算稀疏向量之间的相似度
        IndexParam sparseIndex = IndexParam.builder()
                .fieldName("text_sparse")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build();

        // 一次性为两个字段创建索引
        milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(properties.getCollectionName())
                .indexParams(List.of(denseIndex, sparseIndex))
                .build());

        log.info("Hybrid Collection 已创建: {}", properties.getCollectionName());
    }

    /**
     * 把 Collection 加载到内存。
     * <p>
     * Milvus 的检索必须在内存中进行，创建完 Collection 后必须 load 才能查询。
     * 如果 Collection 已经 load 过了，重复调用 load 不会报错。
     */
    public void loadCollection() {
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(properties.getCollectionName())
                .build());
        log.info("Hybrid Collection 已加载到内存: {}", properties.getCollectionName());
    }

    /**
     * 插入一批医疗健康领域的演示数据。
     * <p>
     * 每条数据只需要提供 text 和 text_dense 两个字段：
     * - text：原始文本，直接写入
     * - text_dense：通过 Spring AI 的 EmbeddingModel 把文本转成 4096 维浮点向量
     * - text_sparse：不需要传！Milvus 的 BM25 Function 会在写入时自动从 text 字段计算生成
     * <p>
     * 这里用医疗健康场景的语料做演示，和 ai 包下的 HR/财务场景区分开，
     * 方便对比不同场景下混合检索的效果。
     */
    public long insertDemoData() {
        List<String> texts = Arrays.asList(
                // 慢性病管理类：适合语义检索（"血压怎么管理"能匹配到）
                "高血压患者日常管理：建议每天早晚各测一次血压并记录，低盐饮食每日钠摄入不超过5克，"
                        + "规律服药不可自行停药，每周至少进行150分钟中等强度有氧运动。",
                // 精确用药类：包含具体药名和数值，适合关键词检索（"二甲双胍 eGFR"能精确匹配）
                "糖尿病 II 型用药指南：二甲双胍是一线用药，起始剂量500mg每日两次随餐服用，"
                        + "肾功能不全（eGFR<30）时禁用，常见副作用为胃肠道不适，通常2周内缓解。",
                // 儿科急症类：包含具体温度和剂量数值
                "儿童发热处理流程：体温38.5°C以下优先物理降温（温水擦浴、退热贴），"
                        + "超过38.5°C可口服布洛芬混悬液（剂量按体重10mg/kg），持续高热超过3天需就医排查感染源。",
                // 急救操作类：包含具体操作步骤和数值参数
                "心肺复苏 CPR 操作要点：确认环境安全后判断意识和呼吸，立即拨打120，"
                        + "胸外按压位置在胸骨下半段，频率100-120次/分钟，深度5-6厘米，每30次按压配合2次人工呼吸。",
                // 产检时间表类：包含具体孕周和检查项目名称
                "孕期产检时间表：孕12周前完成建档和NT检查，孕16-20周做唐氏筛查或无创DNA，"
                        + "孕24-28周做糖耐量试验（OGTT），孕36周后每周产检一次直至分娩。",
                // 骨科急救类：操作指导型内容
                "骨折急救处理：不要随意搬动伤者，用夹板或硬纸板固定骨折部位上下两个关节，"
                        + "开放性骨折用干净纱布覆盖伤口但不要试图将骨头推回，尽快送医处理。"
        );

        // 把每条文本转成 Milvus 需要的 JsonObject 格式
        List<JsonObject> rows = texts.stream()
                .map(this::buildRow)
                .toList();

        // 批量插入到 Hybrid Collection
        InsertResp resp = milvusClient.insert(InsertReq.builder()
                .collectionName(properties.getCollectionName())
                .data(rows)
                .build());

        long count = resp.getInsertCnt();
        log.info("Hybrid Collection 已插入 {} 条演示数据", count);
        return count;
    }

    /**
     * 一站式初始化入口，由 HybridSearchInitializer 在启动时调用。
     * <p>
     * 逻辑：
     * 1. 如果配置了 resetBeforeImport=true，先删掉已有的 Collection（适合反复调试）
     * 2. 如果 Collection 不存在，创建 → 加载 → 插入演示数据
     * 3. 如果 Collection 已存在，只做 load（确保能查询）
     */
    public void initializeIfNeeded() {
        if (properties.isResetBeforeImport()) {
            dropCollectionIfExists();
        }

        if (!collectionExists()) {
            createCollection();
            loadCollection();
            insertDemoData();
        } else {
            // Collection 已存在，确保它被加载到内存（可能上次服务重启后还没 load）
            loadCollection();
        }
    }

    /**
     * 构建一行待插入 Milvus 的数据。
     * <p>
     * Milvus SDK V2 的 insert 接口要求数据是 JsonObject 格式，每个字段对应一个 key。
     * 这里只需要填两个字段：
     * - text：原始文本字符串
     * - text_dense：调用 Spring AI EmbeddingModel 生成的浮点向量数组
     * <p>
     * text_sparse 不需要填！因为我们在 Schema 里注册了 BM25 Function，
     * Milvus 会在数据写入时自动读取 text 字段的内容，做分词，计算 BM25 权重，
     * 然后把生成的稀疏向量写入 text_sparse 字段。
     */
    private JsonObject buildRow(String text) {
        // 调用 Spring AI 的 EmbeddingModel 生成稠密向量
        // 内部会自动调用配置好的 Embedding API（当前是硅基流动的 Qwen3-Embedding-8B）
        // 返回一个 float[]，长度就是配置的 dimensions（4096）
        float[] embedding = embeddingModel.embed(text);

        JsonObject row = new JsonObject();
        // 写入原始文本
        row.addProperty("text", text);

        // 把 float[] 转成 JsonArray，写入 text_dense 字段
        // Milvus 要求向量字段的值是一个数字数组
        JsonArray denseArray = new JsonArray();
        for (float v : embedding) {
            denseArray.add(v);
        }
        row.add("text_dense", denseArray);

        // 注意：这里没有写 text_sparse 字段！
        // 它会由 Milvus 的 BM25 Function 自动生成
        return row;
    }
}
