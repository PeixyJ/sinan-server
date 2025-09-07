package pres.peixinyi.sinan.model.favicon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Favicon提取结果实体类
 * 封装favicon提取操作的完整结果，包括所有找到的图标和最优选择
 * 
 * @author Claude
 * @since 2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaviconExtractResult {
    
    /** 目标网站的域名 */
    private String domain;
    
    /** 原始请求的URL */
    private String url;
    
    /** 所有找到的favicon信息列表 */
    private List<FaviconInfo> faviconList;
    
    /** 最优的favicon选择 */
    private FaviconInfo bestFavicon;
    
    /** 提取操作是否成功 */
    private boolean success;
    
    /** 失败时的错误信息 */
    private String errorMessage;
    
    /** 提取操作耗时（毫秒） */
    private long extractTime;
}