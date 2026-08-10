package com.example.demo.module.interaction.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.module.interaction.vo.AdminCommentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 管理员后台评论管理Mapper接口
 * 负责后台分页查询视频评论列表的数据库操作
 */
@Mapper
public interface AdminCommentMapper {
    /**
     * 后台条件分页查询评论列表
     * @param page MyBatis-Plus分页对象，封装当前页码、每页条数
     * @param videoId 筛选条件：视频ID，查询指定视频下的评论
     * @param keyword 筛选条件：搜索关键词，模糊匹配评论内容
     * @param status 筛选条件：评论状态（正常/禁用/审核中等）
     * @return IPage分页结果对象，内部包含总条数、当前页数据列表等分页信息
     */
    IPage<AdminCommentVO> selectAdminCommentPage(
            Page<AdminCommentVO> page,
            @Param("videoId") Long videoId,
            @Param("keyword") String keyword,
            @Param("status") Integer status
    );
}
