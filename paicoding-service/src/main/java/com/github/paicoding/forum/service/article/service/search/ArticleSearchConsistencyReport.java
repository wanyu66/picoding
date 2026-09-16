package com.github.paicoding.forum.service.article.service.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** MySQL 与 Elasticsearch 的文章索引一致性校验结果。 */
@Data
public class ArticleSearchConsistencyReport {
    private boolean consistent;
    private long mysqlCount;
    private long elasticsearchCount;
    private Date mysqlMaxUpdateTime;
    private Date elasticsearchMaxUpdateTime;
    private int sampleSize;
    private List<Long> mismatchedArticleIds = new ArrayList<>();
    private Date checkTime;
    private String message;
}
