package com.winniethepooh.hotelsystembackend.controller;

import com.winniethepooh.hotelsystembackend.annotation.RoleRequired;
import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.entity.Result;
import com.winniethepooh.hotelsystembackend.exception.ArgumentInvalidException;
import com.winniethepooh.hotelsystembackend.utils.AliOSSUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@RestController
@RequestMapping("/upload")
public class UploadController {
    private final AliOSSUtil aliOSSUtil;

    public UploadController(AliOSSUtil aliOSSUtil) {
        this.aliOSSUtil = aliOSSUtil;
    }

    /** 只允许经理上传；大小上限由 spring.servlet.multipart.max-file-size 控制，超限 413。 */
    @PostMapping("/image")
    @RoleRequired({RoleConstant.MANAGER})
    public Result uploadImage(@RequestParam MultipartFile file) throws IOException {
        checkImage(file);
        return Result.success(aliOSSUtil.upload(file));
    }

    /** 扩展名必须在白名单（jpg、jpeg、png、gif、webp）内，且文件头魔数与扩展名对应的图片格式一致。 */
    private static void checkImage(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String head;
        try (InputStream in = file.getInputStream()) {
            head = new String(in.readNBytes(12), StandardCharsets.ISO_8859_1);
        }
        boolean ok = switch (ext) {
            case "png" -> head.startsWith("\u0089PNG\r\n\u001a\n");
            case "jpg", "jpeg" -> head.startsWith("ÿØÿ");
            case "gif" -> head.startsWith("GIF87a") || head.startsWith("GIF89a");
            case "webp" -> head.startsWith("RIFF") && head.startsWith("WEBP", 8);
            default -> false;
        };
        if (!ok) {
            throw new ArgumentInvalidException("文件类型不支持，仅允许 jpg、jpeg、png、gif、webp 格式的图片");
        }
    }
}
