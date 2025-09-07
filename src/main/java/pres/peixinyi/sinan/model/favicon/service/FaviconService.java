package pres.peixinyi.sinan.model.favicon.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pres.peixinyi.sinan.model.favicon.dto.FaviconExtractResult;
import pres.peixinyi.sinan.model.favicon.dto.FaviconInfo;
import pres.peixinyi.sinan.utils.RedisUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Favicon服务类
 * 提供网站图标的获取、解析、缓存和管理功能
 *
 * @author Claude
 * @since 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaviconService {

    private final FaviconExtractor faviconExtractor;
    private final FaviconCacheService faviconCacheService;
    private final RedisUtils redisUtils;

    @Value("${sinan.server.base-url:http://localhost:8080}")
    private String baseUrl;

    private static final String FAILED_FAVICON_PREFIX = "favicon:failed:";
    private static final long FAILED_FAVICON_TTL = 1; // 1 day

    /**
     * 获取指定URL的favicon信息（不缓存）
     *
     * @param url 目标网站URL
     * @return favicon提取结果
     */
    public FaviconExtractResult getFavicon(String url) {
        return getFavicon(url, false);
    }

    /**
     * 获取指定URL的favicon信息
     *
     * @param url         目标网站URL
     * @param enableCache 是否同时缓存所有找到的favicon到本地
     * @return favicon提取结果，包含所有找到的图标和最优选择
     */
    public FaviconExtractResult getFavicon(String url, boolean enableCache) {
        if (url == null || url.trim().isEmpty()) {
            return FaviconExtractResult.builder()
                    .url(url)
                    .success(false)
                    .errorMessage("URL cannot be empty")
                    .build();
        }

        try {
            FaviconExtractResult result = faviconExtractor.extractFavicons(url);

            if (result.isSuccess() && enableCache) {
                cacheAllFavicons(result);
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to get favicon for URL: {}", url, e);
            return FaviconExtractResult.builder()
                    .url(url)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }


    /**
     * 获取指定URL的最优favicon地址
     *
     * @param url 目标网站URL
     * @return 最优favicon的完整URL，未找到则返回null
     */
    public String getFaviconUrl(String url) {
        FaviconExtractResult result = getFavicon(url);
        if (result.isSuccess() && result.getBestFavicon() != null) {
            return result.getBestFavicon().getUrl();
        }
        return null;
    }

    /**
     * 获取指定URL的最优favicon地址并自动缓存到本地
     *
     * @param url 目标网站URL
     * @return 最优favicon的完整URL，未找到则返回null
     */
    public String getFaviconUrlAndCache(String url) {
        FaviconExtractResult result = getFavicon(url);
        if (result.isSuccess() && result.getBestFavicon() != null) {
            String iconUrl = result.getBestFavicon().getUrl();
            // 自动缓存最优图标
            try {
                faviconCacheService.cacheIcon(iconUrl);
                result.getBestFavicon().setCached(true);
            } catch (Exception e) {
                log.warn("Failed to cache favicon: {}", iconUrl, e);
            }
            return iconUrl;
        }
        return null;
    }

    /**
     * 根据域名和尺寸获取favicon
     * 优先返回本地缓存，如果没有缓存则尝试获取并缓存
     *
     * @param domain 域名，如 vitepress.dev
     * @param size   图标尺寸，可为null
     * @return 缓存文件的本地路径或图标URL
     */
    public String getFaviconByDomainAndSize(String domain, Integer size) {
        if (domain == null || domain.trim().isEmpty()) {
            return null;
        }

        try {
            // 先检查是否已有缓存
            String cachedPath = getCachedFaviconPathByDomain(domain);
            if (cachedPath != null) {
                return cachedPath;
            }

            // 检查是否是获取失败的域名（一天内不重试）
            String failedKey = FAILED_FAVICON_PREFIX + sanitizeDomain(domain);
            if (redisUtils.hasKey(failedKey)) {
                log.debug("Domain {} is marked as failed, skipping retry within 1 day", domain);
                return null;
            }

            // 没有缓存，尝试获取并缓存
            String url = domain.startsWith("http") ? domain : "https://" + domain;
            FaviconExtractResult result = getFavicon(url);

            if (result.isSuccess() && result.getBestFavicon() != null) {
                FaviconInfo bestFavicon = result.getBestFavicon();

                // 如果指定了尺寸，尝试找到匹配尺寸的图标
                if (size != null && result.getFaviconList() != null) {
                    FaviconInfo sizedFavicon = findFaviconBySize(result.getFaviconList(), size);
                    if (sizedFavicon != null) {
                        bestFavicon = sizedFavicon;
                    }
                }

                // 缓存图标（带尺寸信息）
                String iconUrl = bestFavicon.getUrl();
                String cachedFilePath = faviconCacheService.cacheIcon(iconUrl, size);

                // 返回缓存路径或原URL
                return cachedFilePath != null ? cachedFilePath : iconUrl;
            } else {
                // 获取失败，记录到Redis，一天内不再重试
                markDomainAsFailed(domain);
                log.warn("Failed to get favicon for domain: {}, marked as failed for 1 day", domain);
            }

        } catch (Exception e) {
            log.error("Failed to get favicon by domain and size: {} ({})", domain, size, e);
            // 异常情况也记录为失败
            markDomainAsFailed(domain);
        }

        return null;
    }

    /**
     * 根据域名和尺寸获取favicon的服务器访问URL
     * 返回格式：sinan.server.base-url/favicon/xxx
     *
     * @param domain 域名，如 vitepress.dev
     * @param size   图标尺寸，可为null
     * @return 服务器上favicon的访问URL
     */
    public String getFaviconUrlByDomainAndSize(String domain, Integer size) {
        if (domain == null || domain.trim().isEmpty()) {
            return null;
        }

        try {
            // 先检查是否已有缓存（带尺寸）
            String cachedPath = getCachedFaviconPathByDomainAndSize(domain, size);
            if (cachedPath != null) {
                return convertToServerUrl(cachedPath);
            }

            // 检查是否是获取失败的域名（一天内不重试）
            String failedKey = FAILED_FAVICON_PREFIX + sanitizeDomain(domain);
            if (redisUtils.hasKey(failedKey)) {
                log.debug("Domain {} is marked as failed, skipping retry within 1 day", domain);
                return null;
            }

            // 没有缓存，尝试获取并缓存
            String url = domain.startsWith("http") ? domain : "https://" + domain;
            FaviconExtractResult result = getFavicon(url);

            if (result.isSuccess() && result.getBestFavicon() != null) {
                FaviconInfo bestFavicon = result.getBestFavicon();

                // 如果指定了尺寸，尝试找到匹配尺寸的图标
                if (size != null && result.getFaviconList() != null) {
                    FaviconInfo sizedFavicon = findFaviconBySize(result.getFaviconList(), size);
                    if (sizedFavicon != null) {
                        bestFavicon = sizedFavicon;
                    }
                }

                // 缓存图标（带尺寸信息）
                String iconUrl = bestFavicon.getUrl();
                String cachedFilePath = faviconCacheService.cacheIcon(iconUrl, size);

                if (cachedFilePath != null) {
                    return convertToServerUrl(cachedFilePath);
                }
            } else {
                // 获取失败，记录到Redis，一天内不再重试
                markDomainAsFailed(domain);
                log.warn("Failed to get favicon URL for domain: {}, marked as failed for 1 day", domain);
            }

        } catch (Exception e) {
            log.error("Failed to get favicon URL by domain and size: {} ({})", domain, size, e);
            // 异常情况也记录为失败
            markDomainAsFailed(domain);
        }

        return null;
    }

    /**
     * 获取指定URL对应的已缓存favicon文件路径
     *
     * @param url 目标网站URL
     * @return 已缓存文件的本地绝对路径，未缓存则返回null
     */
    public String getCachedFaviconPath(String url) {
        FaviconExtractResult result = getFavicon(url);
        if (result.isSuccess() && result.getBestFavicon() != null) {
            return faviconCacheService.getCachedIconPath(result.getBestFavicon().getUrl());
        }
        return null;
    }

    /**
     * 缓存指定URL的favicon到本地并返回文件路径
     *
     * @param url 目标网站URL
     * @return 缓存文件的本地绝对路径，缓存失败则返回null
     */
    public String cacheAndGetFaviconPath(String url) {
        FaviconExtractResult result = getFavicon(url);
        if (result.isSuccess() && result.getBestFavicon() != null) {
            String iconUrl = result.getBestFavicon().getUrl();
            return faviconCacheService.cacheIcon(iconUrl);
        }
        return null;
    }

    /**
     * 根据域名获取已缓存的favicon文件路径
     *
     * @param domain 域名，如 www.baidu.com
     * @return 已缓存文件的本地绝对路径，格式如 www_baidu_com.png，未缓存则返回null
     */
    public String getCachedFaviconPathByDomain(String domain) {
        if (domain == null || domain.trim().isEmpty()) {
            return null;
        }

        try {
            String sanitizedDomain = sanitizeDomain(domain);
            String fileName = sanitizedDomain.replace(".", "_") + ".png";
            return faviconCacheService.getCachedIconPath("https://" + domain + "/favicon.ico");
        } catch (Exception e) {
            log.error("Failed to get cached favicon by domain: {}", domain, e);
            return null;
        }
    }

    /**
     * 根据域名和尺寸获取已缓存的favicon文件路径
     *
     * @param domain 域名，如 www.baidu.com
     * @param size 图标尺寸，可为null
     * @return 已缓存文件的本地绝对路径，格式如 www_baidu_com_32.png，未缓存则返回null
     */
    public String getCachedFaviconPathByDomainAndSize(String domain, Integer size) {
        if (domain == null || domain.trim().isEmpty()) {
            return null;
        }

        try {
            // 如果没有指定尺寸，使用原方法
            if (size == null) {
                return getCachedFaviconPathByDomain(domain);
            }

            // 构造带尺寸的文件路径进行查找
            String sanitizedDomain = sanitizeDomain(domain);
            Path cacheDir = Paths.get(faviconCacheService.getCacheDir());
            String baseFileName = sanitizedDomain.replace(".", "_");
            
            // 检查各种可能的文件扩展名
            String[] extensions = {".png", ".jpg", ".ico", ".svg", ".webp", ".gif"};
            for (String ext : extensions) {
                String fileName = baseFileName + "_" + size + ext;
                Path filePath = cacheDir.resolve(fileName);
                if (Files.exists(filePath)) {
                    return filePath.toAbsolutePath().toString();
                }
            }
            
            return null;
        } catch (Exception e) {
            log.error("Failed to get cached favicon by domain and size: {} ({})", domain, size, e);
            return null;
        }
    }

    /**
     * 清理指定URL的favicon缓存
     * 删除该URL域名对应的所有本地缓存文件
     *
     * @param url 目标网站URL
     */
    public void clearFaviconCache(String url) {
        try {
            if (url == null || url.trim().isEmpty()) {
                return;
            }

            String domain = extractDomain(url);
            faviconCacheService.clearDomainCache(domain);

        } catch (Exception e) {
            log.error("Failed to clear favicon cache for URL: {}", url, e);
        }
    }

    /**
     * 清理指定域名的favicon缓存
     * 删除该域名对应的所有本地缓存文件
     *
     * @param domain 域名，如 www.baidu.com
     */
    public void clearDomainCache(String domain) {
        faviconCacheService.clearDomainCache(domain);
    }

    /**
     * 清理所有favicon缓存
     * 删除缓存目录下的所有favicon文件
     */
    public void clearAllFaviconCache() {
        faviconCacheService.clearAllCache();
    }

    /**
     * 缓存提取结果中的所有favicon
     * 并行下载所有找到的favicon并保存到本地
     *
     * @param result favicon提取结果
     */
    private void cacheAllFavicons(FaviconExtractResult result) {
        if (result.getFaviconList() != null) {
            result.getFaviconList().parallelStream()
                    .filter(favicon -> favicon.getUrl() != null && !favicon.getUrl().trim().isEmpty())
                    .forEach(favicon -> {
                        try {
                            String cachedPath = faviconCacheService.cacheIcon(favicon.getUrl());
                            favicon.setCached(cachedPath != null);
                        } catch (Exception e) {
                            log.warn("Failed to cache favicon: {}", favicon.getUrl(), e);
                        }
                    });
        }
    }

    /**
     * 从URL中提取域名
     *
     * @param url 完整URL
     * @return 域名部分
     * @throws MalformedURLException URL格式错误时抛出
     */
    private String extractDomain(String url) throws MalformedURLException {
        URL parsedUrl = new URL(url);
        return parsedUrl.getHost();
    }

    /**
     * 清理域名字符串，确保可以作为文件名使用
     * 将不安全的字符替换为下划线
     *
     * @param domain 原始域名
     * @return 清理后的域名字符串
     */
    private String sanitizeDomain(String domain) {
        if (domain == null) {
            return "unknown";
        }
        domain = domain.replace("http://", "").replace("https://", "").replaceAll("/.*$", "");
        return domain.toLowerCase().replaceAll("[^a-z0-9.-]", "_");
    }

    /**
     * 根据尺寸查找最匹配的favicon
     * 优先选择SVG格式，然后选择完全匹配的尺寸，最后选择最接近的尺寸
     *
     * @param faviconList favicon列表
     * @param targetSize  目标尺寸
     * @return 最匹配的favicon，未找到返回null
     */
    private FaviconInfo findFaviconBySize(List<FaviconInfo> faviconList, int targetSize) {
        if (faviconList == null || faviconList.isEmpty()) {
            return null;
        }

        FaviconInfo svgIcon = null;
        FaviconInfo exactMatch = null;
        FaviconInfo closestMatch = null;
        int closestDistance = Integer.MAX_VALUE;

        for (FaviconInfo favicon : faviconList) {
            if (favicon.getUrl() == null) {
                continue;
            }

            // 优先选择SVG格式
            if (isSvgIcon(favicon)) {
                if (svgIcon == null || hasHigherPriority(favicon, svgIcon)) {
                    svgIcon = favicon;
                }
                continue;
            }

            // 处理有尺寸信息的图标
            if (favicon.getSizes() != null) {
                // 解析尺寸信息，如 "16x16", "32x32"
                String[] sizeParts = favicon.getSizes().split("x");
                if (sizeParts.length >= 2) {
                    try {
                        int width = Integer.parseInt(sizeParts[0].trim());

                        if (width == targetSize) {
                            if (exactMatch == null || hasHigherPriority(favicon, exactMatch)) {
                                exactMatch = favicon;
                            }
                        } else {
                            int distance = Math.abs(width - targetSize);
                            if (distance < closestDistance || (distance == closestDistance && hasHigherPriority(favicon, closestMatch))) {
                                closestDistance = distance;
                                closestMatch = favicon;
                            }
                        }
                    } catch (NumberFormatException e) {
                        // 忽略无法解析的尺寸
                    }
                }
            } else {
                // 没有尺寸信息的图标作为备选
                if (closestMatch == null || hasHigherPriority(favicon, closestMatch)) {
                    closestMatch = favicon;
                }
            }
        }

        // 优先级：SVG > 精确匹配 > 最接近匹配
        if (svgIcon != null) {
            return svgIcon;
        }
        return exactMatch != null ? exactMatch : closestMatch;
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

    /**
     * 比较两个favicon的优先级
     *
     * @param favicon1 favicon1
     * @param favicon2 favicon2
     * @return true表示favicon1优先级更高
     */
    private boolean hasHigherPriority(FaviconInfo favicon1, FaviconInfo favicon2) {
        if (favicon1.getPriority() == null && favicon2.getPriority() == null) {
            return false;
        }
        if (favicon1.getPriority() == null) {
            return false;
        }
        if (favicon2.getPriority() == null) {
            return true;
        }
        return favicon1.getPriority() < favicon2.getPriority();
    }

    /**
     * 将本地文件路径转换为服务器访问URL
     * 格式：sinan.server.base-url/favicon/文件名
     *
     * @param localPath 本地文件路径
     * @return 服务器访问URL
     */
    private String convertToServerUrl(String localPath) {
        if (localPath == null) {
            return null;
        }

        try {
            // 从本地路径中提取文件名
            String fileName = localPath.substring(localPath.lastIndexOf("/") + 1);
            
            // 如果文件名是Windows路径格式，也要处理
            if (fileName.equals(localPath) && localPath.contains("\\")) {
                fileName = localPath.substring(localPath.lastIndexOf("\\") + 1);
            }

            // 构造服务器URL，添加context-path前缀
            String serverUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return serverUrl + "/api/favicon/" + fileName;

        } catch (Exception e) {
            log.error("Failed to convert local path to server URL: {}", localPath, e);
            return null;
        }
    }

    /**
     * 标记域名为获取失败，一天内不再重试
     *
     * @param domain 域名
     */
    private void markDomainAsFailed(String domain) {
        try {
            String failedKey = FAILED_FAVICON_PREFIX + sanitizeDomain(domain);
            redisUtils.setEx(failedKey, "1", FAILED_FAVICON_TTL, TimeUnit.DAYS);
            log.debug("Marked domain {} as failed for {} day(s)", domain, FAILED_FAVICON_TTL);
        } catch (Exception e) {
            log.error("Failed to mark domain as failed in Redis: {}", domain, e);
        }
    }

    /**
     * 清除域名的失败标记
     *
     * @param domain 域名
     */
    public void clearFailedMark(String domain) {
        try {
            String failedKey = FAILED_FAVICON_PREFIX + sanitizeDomain(domain);
            redisUtils.delete(failedKey);
            log.debug("Cleared failed mark for domain: {}", domain);
        } catch (Exception e) {
            log.error("Failed to clear failed mark for domain: {}", domain, e);
        }
    }

    /**
     * 检查域名是否被标记为失败
     *
     * @param domain 域名
     * @return true if domain is marked as failed, false otherwise
     */
    public boolean isDomainMarkedAsFailed(String domain) {
        try {
            String failedKey = FAILED_FAVICON_PREFIX + sanitizeDomain(domain);
            return redisUtils.hasKey(failedKey);
        } catch (Exception e) {
            log.error("Failed to check if domain is marked as failed: {}", domain, e);
            return false;
        }
    }
}