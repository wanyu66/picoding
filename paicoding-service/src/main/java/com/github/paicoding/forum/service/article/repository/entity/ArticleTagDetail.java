package com.github.paicoding.forum.service.article.repository.entity;

import lombok.Data;

/** Internal batch-query row; articleId is not added to the public TagDTO. */
@Data
public class ArticleTagDetail {
    private Long articleId;
    private Long tagId;
    private String tag;
}
