package com.example.demo.module.upload.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileUploadVO {

    private String objectName;
    private Integer detectedDuration;

    public FileUploadVO(String objectName) {
        this.objectName = objectName;
    }
}
