package com.github.paicoding.forum.service.video.service;

import com.github.paicoding.forum.service.video.dto.VodUploadAuthDTO;

public interface VideoService {
    /** 创建视频上传凭证。 */
    VodUploadAuthDTO createUploadAuth(String fileName, String title);

    /** 刷新视频上传凭证。 */
    VodUploadAuthDTO refreshUploadAuth(String videoId);

    /** 查询视频播放地址。 */
    String queryPlayUrl(String videoId);
}
