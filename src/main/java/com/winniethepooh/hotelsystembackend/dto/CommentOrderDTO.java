package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

@Data
public class CommentOrderDTO {
    private String type;
    private Integer id;
    private String comment;
}
