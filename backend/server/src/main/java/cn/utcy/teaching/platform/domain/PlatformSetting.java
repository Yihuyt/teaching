package cn.utcy.teaching.platform.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("platform_setting")
public class PlatformSetting {

    @TableId
    private Long id;
    private String siteName;
    private String footerText;
    private Instant updatedAt;

    protected PlatformSetting() {
    }

    public PlatformSetting(Long id, String siteName, String footerText, Instant updatedAt) {
        this.id = id;
        this.siteName = siteName;
        this.footerText = footerText;
        this.updatedAt = updatedAt;
    }

    public void update(String siteName, String footerText) {
        this.siteName = siteName;
        this.footerText = footerText;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSiteName() {
        return siteName;
    }

    public String getFooterText() {
        return footerText;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
