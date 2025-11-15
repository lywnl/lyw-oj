package com.lyw.friend.controller;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.file.domain.OSSResult;
import com.lyw.friend.service.file.IFileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/file")
public class FileController extends BaseController {

    @Autowired
    private IFileService fileService;

    @PostMapping("/upload")
    public Result<OSSResult> upload(@RequestBody MultipartFile file) {
        return Result.ok(fileService.upload(file));
    }
}
