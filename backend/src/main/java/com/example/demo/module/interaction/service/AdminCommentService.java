package com.example.demo.module.interaction.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.interaction.vo.AdminCommentVO;

/**
 * 管理员端评论管理 业务层接口
 * 定义后台管理系统对评论进行操作的抽象方法，由对应的ServiceImpl类实现具体逻辑
 */
public interface AdminCommentService {

    /**
     * 分页查询评论列表（后台管理员使用）
     * @param page 当前页码
     * @param size 每页条数
     * @param videoId 筛选条件：视频ID，传null则查询全部视频的评论
     * @param keyword 搜索关键词，可匹配评论内容、用户名等
     * @param status 评论状态（正常/已删除/违规下架等状态码）
     * @return PageResult 分页对象，内部包含总条数、总页数、当前页数据List<AdminCommentVO>
     */
    PageResult<AdminCommentVO> listComments(
            long page, long size, Long videoId, String keyword, Integer status);

    /**
     * 逻辑删除评论
     * 只修改状态字段为删除，方便后续恢复
     * @param commentId 评论主键ID
     */
    void deleteComment(Long commentId);

    /**
     * 恢复已逻辑删除的评论
     * 将评论状态改回正常，前端重新展示
     * @param commentId 评论主键ID
     */
    void restoreComment(Long commentId);
}
