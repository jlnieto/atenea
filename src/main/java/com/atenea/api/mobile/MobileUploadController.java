package com.atenea.api.mobile;

import com.atenea.service.mobile.MobileUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mobile/uploads")
public class MobileUploadController {

    private final MobileUploadService mobileUploadService;

    public MobileUploadController(MobileUploadService mobileUploadService) {
        this.mobileUploadService = mobileUploadService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MobileUploadResponse upload(@RequestPart("file") MultipartFile file) {
        return mobileUploadService.store(file);
    }
}
