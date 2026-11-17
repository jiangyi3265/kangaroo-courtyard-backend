package com.ruoyi.kangaroo.testsupport;

/** 老业务用例也须满足上线后的照片必填规则，文件真实存在且隔离于业务上传目录。 */
public abstract class WithJobPhoto {
    protected static final String PHOTO = "/profile/upload/regression-fixture.png";
    private UploadedImageFixture files;
    @org.junit.jupiter.api.BeforeEach void createPhoto() { files = new UploadedImageFixture(PHOTO); }
    @org.junit.jupiter.api.AfterEach void removePhoto() { files.close(); }
}
