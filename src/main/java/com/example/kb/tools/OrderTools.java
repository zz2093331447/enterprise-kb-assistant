package com.example.kb.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OrderTools {
    // 用内存 Map 模拟"订单数据库"。
    // 真实项目这里会注入 JpaRepository 或 RestTemplate 去调订单服务，写法结构完全不变。
    private static final Map<String, String> ORDERS = Map.of(
            "NO2026001", "订单 NO2026001：状态=已发货，金额=299.00元，收货人=张三，物流=顺丰已揽收",
            "NO2026002", "订单 NO2026002：状态=待付款，金额=1599.00元，收货人=李四，下单时间=2026-11-10",
            "NO2026003", "订单 NO2026003：状态=已签收，金额=89.90元，收货人=王五，签收时间=2026-11-12");

    @Tool(description = "根据订单号查询订单的状态、金额、收货人、物流等详情。参数使用订单号，例如 NO2026001。")
    public String queryOrder(@ToolParam(description = "订单号，例如 NO2026001") String orderId) {
        return ORDERS.getOrDefault(orderId, "未找到订单" + orderId + "，请确认订单号是否正确");
    }
}