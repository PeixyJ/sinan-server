package pres.peixinyi.sinan.model.favicon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Favicon信息实体类
 * 封装单个favicon图标的详细信息，包括URL、类型、尺寸等属性
 * 
 * @author Claude
 * @since 2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaviconInfo {
    
    /** 图标的完整URL地址 */
    private String url;
    
    /** 原始href属性值（相对或绝对路径） */
    private String href;
    
    /** 图标的MIME类型，如 image/png */
    private String type;
    
    /** 图标尺寸，如 16x16, 32x32 */
    private String sizes;
    
    /** 主题颜色值，如 #ffffff */
    private String color;
    
    /** Favicon类型枚举 */
    private FaviconType faviconType;
    
    /** 优先级，数字越小优先级越高 */
    private Integer priority;
    
    /** 是否已缓存到本地 */
    private boolean cached;
    
    /**
     * Favicon类型枚举
     * 定义了7种支持的favicon类型及其默认优先级
     */
    public enum FaviconType {
        DEFAULT_FAVICON("favicon.ico", 1),
        ICON("icon", 2), 
        APPLE_TOUCH_ICON("apple-touch-icon", 3),
        TWITTER_IMAGE("twitter:image", 4),
        MASK_ICON("mask-icon", 5),
        SHORTCUT_ICON("shortcut icon", 6),
        THEME_COLOR("theme-color", 7);
        
        private final String rel;
        private final int defaultPriority;
        
        FaviconType(String rel, int defaultPriority) {
            this.rel = rel;
            this.defaultPriority = defaultPriority;
        }
        
        public String getRel() {
            return rel;
        }
        
        public int getDefaultPriority() {
            return defaultPriority;
        }
    }
}