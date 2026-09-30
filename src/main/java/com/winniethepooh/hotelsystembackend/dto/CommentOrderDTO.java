package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class CommentOrderDTO {
    @NotBlank(message = "订单类型不能为空")
    private String type;
    @NotNull(message = "订单 id 不能为空")
    private Integer id;
    @Size(max = 500, message = "评价不能超过500字")
    private String comment;
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分必须在1到5之间")
    @Max(value = 5, message = "评分必须在1到5之间")
    private Integer commentStar;
}
