# Favicon 获取功能

该功能包实现了从网站获取图标的完整解决方案，支持7种不同类型的favicon获取方式。图标将根据域名保存为本地文件，无需Redis依赖。

## 功能特性

### 支持的Favicon类型

1. **默认favicon.ico** - 网站根目录的 `/favicon.ico` 文件
2. **标准icon标签** - `<link rel="icon" href="/path/to/favicon.ico" type="image/x-icon">`
3. **Apple touch icon** - `<link rel="apple-touch-icon" href="/path/to/apple-touch-icon.png">`
4. **Twitter图片** - `<meta name="twitter:image" content="/path/to/twitter-icon.png">`
5. **Safari mask icon** - `<link rel="mask-icon" href="/path/to/safari-pinned-tab.svg" color="#5bbad5">`
6. **主题颜色** - `<meta name="theme-color" content="#ffffff">`
7. **快捷方式图标** - `<link rel="shortcut icon" href="/path/to/favicon.ico" type="image/x-icon">`

### 核心组件

#### 1. FaviconInfo
封装单个favicon的信息，包含：
- url: 图标的绝对URL
- href: 原始href属性
- type: MIME类型
- sizes: 图标尺寸
- color: 主题颜色
- faviconType: 图标类型枚举
- priority: 优先级（数字越小优先级越高）
- cached: 是否已缓存

#### 2. FaviconExtractor
核心提取器，负责：
- 获取网页HTML内容
- 使用JSoup解析HTML
- 提取各种类型的favicon链接
- 检查默认favicon.ico可用性
- 选择最优favicon

#### 3. FaviconCacheService
缓存服务，提供：
- 图标本地文件缓存
- 基于域名的文件命名（例如：www.baidu.com -> www_baidu_com.png）
- 自动文件管理
- 缓存清理功能

#### 4. FaviconService
主服务类，提供：
- favicon获取和解析
- 缓存管理
- 最优图标选择

#### 5. FaviconController
REST API控制器，提供：
- `GET /favicon/extract` - 提取favicon信息
- `GET /favicon/url` - 获取最优favicon URL并自动缓存
- `GET /favicon/icon` - 根据域名和尺寸获取favicon（支持domain&sz参数）
- `POST /favicon/cache` - 缓存favicon到本地
- `GET /favicon/cache/path` - 获取已缓存的favicon路径
- `GET /favicon/cache/domain` - 根据域名获取缓存路径
- `DELETE /favicon/cache` - 清理指定URL的缓存
- `DELETE /favicon/cache/domain` - 清理指定域名的缓存
- `DELETE /favicon/cache/all` - 清理所有缓存

#### 6. FaviconStaticController
静态文件控制器，提供：
- `GET /favicon/{filename}` - 直接访问缓存的favicon文件

## 使用方法

### 1. 基本使用

```java
@Autowired
private FaviconService faviconService;

// 获取favicon
FaviconExtractResult result = faviconService.getFavicon("https://example.com");
if (result.isSuccess()) {
    FaviconInfo bestFavicon = result.getBestFavicon();
    System.out.println("最佳图标URL: " + bestFavicon.getUrl());
}

// 获取并缓存favicon
String cachedPath = faviconService.cacheAndGetFaviconPath("https://example.com");
```

### 2. REST API使用

```bash
# 提取favicon信息
curl "http://localhost:8080/favicon/extract?url=https://github.com"

# 获取最优favicon URL并自动缓存
curl "http://localhost:8080/favicon/url?url=https://github.com"

# 根据域名获取favicon（自动缓存并返回服务器URL）
curl "http://localhost:8080/favicon/icon?domain=vitepress.dev"
# 返回: http://localhost:8080/favicon/vitepress_dev.png

# 根据域名和尺寸获取favicon
curl "http://localhost:8080/favicon/icon?domain=vitepress.dev&sz=32"
# 返回: http://localhost:8080/favicon/vitepress_dev_32.png

# 直接访问缓存的favicon文件
curl "http://localhost:8080/favicon/vitepress_dev.png"

# 缓存favicon到本地
curl -X POST "http://localhost:8080/favicon/cache?url=https://github.com"

# 根据域名获取缓存路径
curl "http://localhost:8080/favicon/cache/domain?domain=github.com"

# 清理指定URL的缓存
curl -X DELETE "http://localhost:8080/favicon/cache?url=https://github.com"

# 清理指定域名的缓存
curl -X DELETE "http://localhost:8080/favicon/cache/domain?domain=github.com"

# 清理所有缓存
curl -X DELETE "http://localhost:8080/favicon/cache/all"
```

## 配置说明

在 `application.yaml` 中配置：

```yaml
favicon:
  cache:
    # favicon缓存目录
    dir: ./upload/icons
```

## 文件命名规则

图标文件根据域名自动命名，规则如下：
- `www.baidu.com` → `www_baidu_com.png`
- `github.com` → `github_com.png`
- `stackoverflow.com` → `stackoverflow_com.png`
- `sub.example.org` → `sub_example_org.png`

域名中的点(.)会被替换为下划线(_)，文件扩展名根据图标实际格式确定。

## 优先级规则

系统按以下优先级选择最优图标：
1. 默认favicon.ico (优先级1)
2. 标准icon (优先级2) 
3. Apple touch icon (优先级3)
4. Twitter image (优先级4)
5. Safari mask icon (优先级5)
6. 快捷方式图标 (优先级6)
7. 主题颜色 (优先级7)

相同优先级情况下，优先选择尺寸更大的图标。

## 依赖说明

- **Spring Boot** - 基础框架
- **OkHttp** - HTTP客户端
- **JSoup** - HTML解析
- **Lombok** - 代码简化

## 新功能特性

### 自动缓存
- 获取最优icon时自动存储到本地
- `/api/favicon/url` 接口会自动缓存最优图标
- `/api/favicon/icon` 接口支持域名直接访问并自动缓存

### 域名参数支持
- 支持 `domain=vitepress.dev&sz=32` 格式的请求参数
- `domain` - 指定域名
- `sz` - 指定图标尺寸（可选）

### 智能尺寸匹配
- 支持按指定尺寸查找最匹配的favicon
- 优先完全匹配，其次选择最接近的尺寸
- 文件名支持尺寸后缀，如 `vitepress_dev_32.png`

## 更新说明

- **v2.2** - 新增自动缓存和域名参数支持
- **v2.1** - 移除异步编程，简化代码结构
- **v2.0** - 移除Redis依赖，使用本地文件存储
- 根据域名自动生成文件名（例如：www.baidu.com -> www_baidu_com.png）
- 支持按域名查询和清理缓存
- 简化配置，只需指定缓存目录