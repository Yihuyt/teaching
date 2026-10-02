package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("programming_problem_provenance")
public class ProgrammingProblemProvenance {

    @TableId
    private Long problemId;
    private String problemFormatVersion;
    private String packageUuid;
    private String packageVersion;
    private String sourcesJson;
    private String creditsJson;
    private String licenseCode;
    private String rightsOwner;
    private String sourcePackageSha256;
    private long sourcePackageSizeBytes;
    private String sourcePackageObjectKey;
    private Instant importedAt;

    protected ProgrammingProblemProvenance() {
    }

    public ProgrammingProblemProvenance(
            Long problemId,
            String problemFormatVersion,
            String packageUuid,
            String packageVersion,
            String sourcesJson,
            String creditsJson,
            String licenseCode,
            String rightsOwner,
            String sourcePackageSha256,
            long sourcePackageSizeBytes,
            String sourcePackageObjectKey,
            Instant importedAt
    ) {
        this.problemId = problemId;
        this.problemFormatVersion = problemFormatVersion;
        this.packageUuid = packageUuid;
        this.packageVersion = packageVersion;
        this.sourcesJson = sourcesJson;
        this.creditsJson = creditsJson;
        this.licenseCode = licenseCode;
        this.rightsOwner = rightsOwner;
        this.sourcePackageSha256 = sourcePackageSha256;
        this.sourcePackageSizeBytes = sourcePackageSizeBytes;
        this.sourcePackageObjectKey = sourcePackageObjectKey;
        this.importedAt = importedAt;
    }

    public Long getProblemId() {
        return problemId;
    }

    public String getProblemFormatVersion() {
        return problemFormatVersion;
    }

    public String getPackageUuid() {
        return packageUuid;
    }

    public String getPackageVersion() {
        return packageVersion;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public String getCreditsJson() {
        return creditsJson;
    }

    public String getLicenseCode() {
        return licenseCode;
    }

    public String getRightsOwner() {
        return rightsOwner;
    }

    public String getSourcePackageSha256() {
        return sourcePackageSha256;
    }

    public long getSourcePackageSizeBytes() {
        return sourcePackageSizeBytes;
    }

    public String getSourcePackageObjectKey() {
        return sourcePackageObjectKey;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
