package dev.llmlint.sample;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OrderTools {

    private final JdbcClient jdbc;

    public OrderTools(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(description = "Order status for a customer")
    public String orderStatus(@ToolParam(description = "Customer id") String customerId) {
        Integer count = jdbc.sql("select count(*) from orders where customer_id = ?")
                .param(customerId)
                .query(Integer.class)
                .single();
        return "Customer " + customerId + " has " + count + " orders";
    }

    /**
     * BUG (intentional): a side-effecting tool without an idempotency key.
     * spring.ai.retry re-sends the whole model call on 429/5xx; if the model calls this
     * tool again on the retried attempt, the same order is inserted twice.
     */
    @Tool(description = "Place a new order for a customer")
    public String placeOrder(@ToolParam(description = "Customer id") String customerId,
                             @ToolParam(description = "Product SKU") String sku,
                             @ToolParam(description = "Quantity") int quantity) {
        jdbc.sql("insert into orders (customer_id, sku, quantity) values (?, ?, ?)")
                .params(customerId, sku, quantity)
                .update();
        return "Order placed";
    }
}
