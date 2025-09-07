package pres.peixinyi.sinan.model.favicon.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import pres.peixinyi.sinan.model.favicon.dto.FaviconExtractResult;
import pres.peixinyi.sinan.model.favicon.dto.FaviconInfo;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Favicon提取器
 * 负责从网页HTML中提取各种类型的favicon信息
 * 支持7种类型：默认favicon.ico、icon标签、apple-touch-icon、twitter:image、mask-icon、theme-color、shortcut icon
 * 
 * @author Claude
 * @since 2.0
 */
@Slf4j
@Component
public class FaviconExtractor {

    private final OkHttpClient httpClient;
    
    private static final String DEFAULT_FAVICON_PATH = "/favicon.ico";
    private static final int TIMEOUT_SECONDS = 10;

    /**
     * 构造函数，初始化HTTP客户端
     * 设置统一的超时时间为10秒
     */
    public FaviconExtractor() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .readTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .writeTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    /**
     * 从指定URL提取所有favicon信息
     * 包括从 HTML 中解析各种类型的图标链接，检查默认favicon.ico，选择最优图标
     * 
     * @param url 目标网站URL
     * @return 包含所有找到的favicon信息和最优选择的提取结果
     */
    public FaviconExtractResult extractFavicons(String url) {
        long startTime = System.currentTimeMillis();
        
        try {
            URL parsedUrl = new URL(url);
            String domain = parsedUrl.getHost();
            String baseUrl = parsedUrl.getProtocol() + "://" + parsedUrl.getHost() + 
                           (parsedUrl.getPort() != -1 ? ":" + parsedUrl.getPort() : "");
            
            List<FaviconInfo> faviconList = new ArrayList<>();
            
            String htmlContent = fetchHtmlContent(url);
            if (htmlContent != null) {
                extractFromHtml(htmlContent, baseUrl, faviconList);
            }
            
            checkDefaultFavicon(baseUrl, faviconList);
            
            FaviconInfo bestFavicon = selectBestFavicon(faviconList);
            
            return FaviconExtractResult.builder()
                    .domain(domain)
                    .url(url)
                    .faviconList(faviconList)
                    .bestFavicon(bestFavicon)
                    .success(true)
                    .extractTime(System.currentTimeMillis() - startTime)
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to extract favicons from URL: {}", url, e);
            return FaviconExtractResult.builder()
                    .url(url)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .extractTime(System.currentTimeMillis() - startTime)
                    .build();
        }
    }
    
    /**
     * 获取指定URL的HTML内容
     * 
     * @param url 目标URL
     * @return HTML内容字符串，失败返回null
     */
    private String fetchHtmlContent(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (compatible; FaviconExtractor/1.0)")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch HTML content from: {}", url, e);
        }
        return null;
    }
    
    /**
     * 从HTML内容中提取favicon信息
     * 
     * @param htmlContent HTML内容
     * @param baseUrl 基本URL，用于解析相对路径
     * @param faviconList 用于存储提取结果的列表
     */
    private void extractFromHtml(String htmlContent, String baseUrl, List<FaviconInfo> faviconList) {
        try {
            Document doc = Jsoup.parse(htmlContent);
            
            extractIconLinks(doc, baseUrl, faviconList);
            extractMetaTags(doc, baseUrl, faviconList);
            
        } catch (Exception e) {
            log.warn("Failed to parse HTML content", e);
        }
    }
    
    /**
     * 提取HTML中link标签的图标信息
     * 支持icon、shortcut icon、apple-touch-icon、mask-icon类型
     * 
     * @param doc JSoup解析的HTML文档
     * @param baseUrl 基本URL
     * @param faviconList 结果存储列表
     */
    private void extractIconLinks(Document doc, String baseUrl, List<FaviconInfo> faviconList) {
        Elements iconLinks = doc.select("link[rel~=(?i)(icon|shortcut\\s+icon|apple-touch-icon|mask-icon)]");
        
        for (Element link : iconLinks) {
            String rel = link.attr("rel").toLowerCase().trim();
            String href = link.attr("href");
            
            if (href == null || href.trim().isEmpty()) {
                continue;
            }
            
            String absoluteHref = resolveUrl(baseUrl, href.trim());
            String type = link.attr("type");
            String sizes = link.attr("sizes");
            String color = link.attr("color");
            
            FaviconInfo.FaviconType faviconType = determineFaviconType(rel);
            
            FaviconInfo faviconInfo = FaviconInfo.builder()
                    .url(absoluteHref)
                    .href(href.trim())
                    .type(type.isEmpty() ? null : type)
                    .sizes(sizes.isEmpty() ? null : sizes)
                    .color(color.isEmpty() ? null : color)
                    .faviconType(faviconType)
                    .priority(faviconType.getDefaultPriority())
                    .cached(false)
                    .build();
            
            faviconList.add(faviconInfo);
        }
    }
    
    /**
     * 提取HTML中meta标签的图标信息
     * 支持twitter:image和theme-color类型
     * 
     * @param doc JSoup解析的HTML文档
     * @param baseUrl 基本URL
     * @param faviconList 结果存储列表
     */
    private void extractMetaTags(Document doc, String baseUrl, List<FaviconInfo> faviconList) {
        Elements twitterImageMeta = doc.select("meta[name=twitter:image]");
        for (Element meta : twitterImageMeta) {
            String content = meta.attr("content");
            if (!content.isEmpty()) {
                String absoluteUrl = resolveUrl(baseUrl, content);
                
                FaviconInfo faviconInfo = FaviconInfo.builder()
                        .url(absoluteUrl)
                        .href(content)
                        .type("image/png")
                        .faviconType(FaviconInfo.FaviconType.TWITTER_IMAGE)
                        .priority(FaviconInfo.FaviconType.TWITTER_IMAGE.getDefaultPriority())
                        .cached(false)
                        .build();
                
                faviconList.add(faviconInfo);
            }
        }
        
        Elements themeColorMeta = doc.select("meta[name=theme-color]");
        for (Element meta : themeColorMeta) {
            String content = meta.attr("content");
            if (!content.isEmpty()) {
                FaviconInfo faviconInfo = FaviconInfo.builder()
                        .color(content)
                        .faviconType(FaviconInfo.FaviconType.THEME_COLOR)
                        .priority(FaviconInfo.FaviconType.THEME_COLOR.getDefaultPriority())
                        .cached(false)
                        .build();
                
                faviconList.add(faviconInfo);
            }
        }
    }
    
    /**
     * 检查默认favicon.ico文件是否存在
     * 如果列表中没有默认favicon且根目录下的favicon.ico可访问，则添加到列表
     * 
     * @param baseUrl 网站基本URL
     * @param faviconList favicon列表
     */
    private void checkDefaultFavicon(String baseUrl, List<FaviconInfo> faviconList) {
        boolean hasDefaultFavicon = faviconList.stream()
                .anyMatch(f -> f.getFaviconType() == FaviconInfo.FaviconType.DEFAULT_FAVICON);
        
        if (!hasDefaultFavicon) {
            String defaultFaviconUrl = baseUrl + DEFAULT_FAVICON_PATH;
            if (isUrlAccessible(defaultFaviconUrl)) {
                FaviconInfo faviconInfo = FaviconInfo.builder()
                        .url(defaultFaviconUrl)
                        .href(DEFAULT_FAVICON_PATH)
                        .type("image/x-icon")
                        .faviconType(FaviconInfo.FaviconType.DEFAULT_FAVICON)
                        .priority(FaviconInfo.FaviconType.DEFAULT_FAVICON.getDefaultPriority())
                        .cached(false)
                        .build();
                
                faviconList.add(faviconInfo);
            }
        }
    }
    
    /**
     * 从所有favicon中选择最优的一个
     * 按优先级排序，相同优先级下选择尺寸更大的
     * 
     * @param faviconList 所有找到的favicon列表
     * @return 最优favicon，无可用图标返回null
     */
    private FaviconInfo selectBestFavicon(List<FaviconInfo> faviconList) {
        if (faviconList.isEmpty()) {
            return null;
        }
        
        // 首先查找SVG格式的图标
        FaviconInfo svgIcon = faviconList.stream()
                .filter(f -> f.getUrl() != null && !f.getUrl().trim().isEmpty())
                .filter(f -> f.getFaviconType() != FaviconInfo.FaviconType.THEME_COLOR)
                .filter(this::isSvgIcon)
                .min(Comparator.comparingInt(FaviconInfo::getPriority))
                .orElse(null);
                
        // 如果找到SVG图标，优先返回
        if (svgIcon != null) {
            return svgIcon;
        }
        
        // 否则按原有逻辑选择
        return faviconList.stream()
                .filter(f -> f.getUrl() != null && !f.getUrl().trim().isEmpty())
                .filter(f -> f.getFaviconType() != FaviconInfo.FaviconType.THEME_COLOR)
                .min(Comparator.comparingInt(FaviconInfo::getPriority)
                     .thenComparing(f -> parseSizeValue(f.getSizes()), Comparator.reverseOrder()))
                .orElse(faviconList.stream()
                        .filter(f -> f.getUrl() != null && !f.getUrl().trim().isEmpty())
                        .findFirst()
                        .orElse(faviconList.get(0)));
    }

    /**
     * 检查是否为SVG图标
     *
     * @param favicon favicon信息
     * @return true表示是SVG图标
     */
    private boolean isSvgIcon(FaviconInfo favicon) {
        if (favicon.getType() != null && favicon.getType().toLowerCase().contains("svg")) {
            return true;
        }
        if (favicon.getUrl() != null && favicon.getUrl().toLowerCase().endsWith(".svg")) {
            return true;
        }
        return favicon.getFaviconType() == FaviconInfo.FaviconType.MASK_ICON;
    }
    
    private FaviconInfo.FaviconType determineFaviconType(String rel) {
        if (rel.contains("apple-touch-icon")) {
            return FaviconInfo.FaviconType.APPLE_TOUCH_ICON;
        } else if (rel.contains("mask-icon")) {
            return FaviconInfo.FaviconType.MASK_ICON;
        } else if (rel.contains("shortcut")) {
            return FaviconInfo.FaviconType.SHORTCUT_ICON;
        } else {
            return FaviconInfo.FaviconType.ICON;
        }
    }
    
    private String resolveUrl(String baseUrl, String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        
        if (href.startsWith("//")) {
            try {
                URL base = new URL(baseUrl);
                return base.getProtocol() + ":" + href;
            } catch (MalformedURLException e) {
                return "https:" + href;
            }
        }
        
        if (href.startsWith("/")) {
            return baseUrl + href;
        }
        
        return baseUrl + "/" + href;
    }
    
    private boolean isUrlAccessible(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .head()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    private int parseSizeValue(String sizes) {
        if (sizes == null || sizes.trim().isEmpty()) {
            return 0;
        }
        
        String[] parts = sizes.split("x");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}