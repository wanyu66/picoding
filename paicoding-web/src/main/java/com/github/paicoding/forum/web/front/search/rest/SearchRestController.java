package com.github.paicoding.forum.web.front.search.rest;

import com.github.paicoding.forum.api.model.vo.NextPageHtmlVo;
import com.github.paicoding.forum.api.model.vo.PageListVo;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.vo.ResVo;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleDTO;
import com.github.paicoding.forum.api.model.vo.article.dto.SimpleArticleDTO;
import com.github.paicoding.forum.service.article.service.ArticleReadService;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchService;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchRuntimeStatus;
import com.github.paicoding.forum.web.component.TemplateEngineHelper;
import com.github.paicoding.forum.web.front.search.vo.SearchArticleVo;
import com.github.paicoding.forum.web.global.BaseViewController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 推荐服务接口
 *
 * @author YiHui
 * @date 2022/10/28
 */
@RequestMapping(path = "search/api")
@RestController
public class SearchRestController extends BaseViewController {

    @Autowired
    private ArticleReadService articleReadService;

    @Autowired
    private TemplateEngineHelper templateEngineHelper;

    @Autowired
    private ArticleSearchService articleSearchService;

    @Value("${elasticsearch.search-max-page:50}")
    private long searchMaxPage;

    /**
     * 根据关键词给出搜索下拉框
     *
     * @param key
     */
    @GetMapping(path = "hint")
    public ResVo<SearchArticleVo> recommend(@RequestParam(name = "key", required = false) String key) {List<SimpleArticleDTO> list = articleReadService.querySimpleArticleBySearchKey(key);
        SearchArticleVo vo = new SearchArticleVo();
        vo.setKey(key);
        vo.setItems(list);
        vo.setSearchState(articleSearchService.runtimeStatus().getState().name());
        return ResVo.ok(vo);
    }


    /**
     * 分类下的文章列表
     *
     * @param key
     * @return
     */
    @GetMapping(path = "list")
    public ResVo<NextPageHtmlVo> searchList(@RequestParam(name = "key", required = false) String key,
                                            @RequestParam(name = "page") Long page,
                                            @RequestParam(name = "size", required = false) Long size) {
        if (page == null || page < 1 || page > Math.max(1, searchMaxPage)) {
            throw new IllegalArgumentException("搜索页码必须在 1 到 " + searchMaxPage + " 之间");
        }
        PageParam pageParam = buildPageParam(page, size);
        PageListVo<ArticleDTO> list = articleReadService.queryArticlesBySearchKey(key, pageParam);
        String html = templateEngineHelper.renderToVo("views/article-search-list/article/list", "articles", list);
        return ResVo.ok(new NextPageHtmlVo(html, list.getHasMore(),
                articleSearchService.runtimeStatus().getState().name()));
    }

    @GetMapping(path = "status")
    public ResVo<ArticleSearchRuntimeStatus> status() {
        return ResVo.ok(articleSearchService.runtimeStatus());
    }

    @GetMapping(path = "hot")
    public ResVo<List<String>> hotQueries(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResVo.ok(articleSearchService.hotQueries(limit));
    }

    @GetMapping(path = "suggestions")
    public ResVo<List<String>> suggestions(@RequestParam(name = "key", required = false) String key,
                                            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResVo.ok(articleSearchService.suggestQueries(key, limit));
    }

    @GetMapping(path = "zero-result")
    public ResVo<List<String>> zeroResultQueries(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ResVo.ok(articleSearchService.zeroResultQueries(limit));
    }

    @PostMapping(path = "click")
    public ResVo<Void> recordClick(@RequestParam(name = "key") String key,
                                   @RequestParam(name = "articleId") Long articleId) {
        articleSearchService.recordSearchClick(key, articleId);
        return ResVo.ok(null);
    }
}
