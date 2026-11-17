package com.ruoyi.kangaroo.ext;

import java.util.List;

/** 模块把自己的后台通用实体交给 ExtEntities 的接口 */
public interface ExtEntityContributor
{
    List<ExtEntity> entities();
}
