package com.videonest.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videonest.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * SysUserMapper 数据访问映射接口。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
