package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.util.List;

@Data
public class PageBean<T> {
    private Integer total;
    private List<T> list;
}
