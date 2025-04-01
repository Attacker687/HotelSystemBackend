package com.winniethepooh.hotelsystembackend.entity;

import com.winniethepooh.hotelsystembackend.constant.ResultCodeConstant;
import lombok.Data;

@Data
public class Result {
    private int code;
    private String msg;
    private Object data;
    Result(int code, String msg, Object data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }
    public static Result success(){return new Result(ResultCodeConstant.SUCCESS, "success", null);}
    public static Result success(Object data){return new Result(ResultCodeConstant.SUCCESS, "success", data);}
    public static Result error(String msg){return new Result(ResultCodeConstant.ERROR, msg, null);}
}
