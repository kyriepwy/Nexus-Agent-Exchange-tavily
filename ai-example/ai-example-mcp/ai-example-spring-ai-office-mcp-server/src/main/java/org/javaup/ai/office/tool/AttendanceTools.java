package org.javaup.ai.office.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 工具类
 * @author: 阿星不是程序员
 **/
@Service
public class AttendanceTools {

    /**
     * 查询员工考勤记录
     * 大模型会根据description判断何时使用此工具
     */
    @Tool(description = "查询员工的考勤记录，包括出勤天数、迟到次数、早退次数、请假天数。" +
                        "当用户询问考勤、打卡、出勤等相关问题时使用此工具。")
    public String checkAttendance(
            @ToolParam(description = "员工工号，如E10086") String employeeId,
            @ToolParam(description = "查询月份，格式YYYY-MM，如2025-03") String month) {
        
        // 实际项目中这里调用HR系统API
        // 这里用模拟数据演示
        return String.format("""
            {
                "employeeId": "%s",
                "month": "%s",
                "workDays": 22,
                "actualDays": 21,
                "lateTimes": 2,
                "earlyLeaveTimes": 0,
                "leaveDays": 1,
                "overtimeHours": 8.5
            }
            """, employeeId, month);
    }
    
    /**
     * 员工签到打卡
     */
    @Tool(description = "员工进行签到打卡操作。当用户说要打卡、签到时使用此工具。")
    public String clockIn(
            @ToolParam(description = "员工工号") String employeeId,
            @ToolParam(description = "打卡类型：IN表示上班签到，OUT表示下班签退") String type) {
        
        String currentTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        String status = "IN".equalsIgnoreCase(type) ? "上班签到" : "下班签退";
        
        return String.format("""
            {
                "success": true,
                "employeeId": "%s",
                "clockType": "%s",
                "clockTime": "%s",
                "message": "%s成功"
            }
            """, employeeId, type, currentTime, status);
    }
}