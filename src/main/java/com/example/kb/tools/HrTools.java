package com.example.kb.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class HrTools {
    private static final Map<String, String> EMPLOYEES = Map.of(
            "张三", "工号 E1001，研发部，入职日期 2023-04-01",
            "李四", "工号 E1002，产品部，入职日期 2022-09-15",
            "王五", "工号 E1003，财务部，入职日期 2021-01-10");
    private static final Map<String, Integer> LEAVE_BALANCE = Map.of(
            "张三", 8, "李四", 3, "王五", 12);
    @Tool(description = "查询员工的部门、工号、入职日期等基本信息。参数使用员工姓名。")
    public String lookupEmployeeInfo(@ToolParam(description = "员工姓名，例如 张三") String name) {
        return EMPLOYEES.getOrDefault(name, "未找到该员工"+name);
    }
    @Tool(description = "查询员工的年假余额。参数使用员工姓名。")
    public String checkBalance(@ToolParam(description = "员工姓名，例如 张三") String name){
        int days = LEAVE_BALANCE.getOrDefault(name, 0);
        return name +"当前剩余年假"+days+"天";
    }

}
