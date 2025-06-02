package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.utils.AliOSSUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/upload")
public class UploadController {
    private final AliOSSUtil aliOSSUtil;

    public UploadController(AliOSSUtil aliOSSUtil) {
        this.aliOSSUtil = aliOSSUtil;
    }

    @PostMapping("/image")
    public Result uploadImage(@RequestParam MultipartFile file) throws IOException {
        return Result.success(aliOSSUtil.upload(file));
    }
}
