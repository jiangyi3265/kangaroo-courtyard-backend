package com.ruoyi.kangaroo.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 门店 对象 kg_store
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgStore extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 门店ID */
    @Excel(name = "门店ID")
    private Long storeId;

    /** 门店名称 */
    @Excel(name = "门店名称")
    private String storeName;

    /** 城市 */
    @Excel(name = "城市")
    private String city;

    /** 详细地址 */
    @Excel(name = "详细地址")
    private String address;

    /** 封面图 */
    private String cover;

    /** 有序门店图片（JSON 数组），第一张同步到 cover；null 表示本次未修改图片。 */
    private String images;

    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }

    /** 门店简介 */
    private String intro;

    /** 对外联系电话 */
    @Excel(name = "联系电话")
    private String contactPhone;

    /** 安心商家认证（0已认证 1未认证） */
    @Excel(name = "安心认证", readConverterExp = "0=已认证,1=未认证")
    private String safeCert;

    /** 认证时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date certTime;

    /** 认证操作人 */
    private String certBy;

    /** 综合评分 */
    @Excel(name = "综合评分")
    private BigDecimal rating;

    /** 累计评价数 */
    private Integer reviewCount;

    /** 运营类型（direct 直营 / franchise 加盟 / managed 代运营 / partner 合作，V70 加合作）：决定收费活动是否由平台在线代收（只有直营代收） */
    @Excel(name = "运营类型", readConverterExp = "direct=直营,franchise=加盟,managed=代运营,partner=合作")
    private String opType;

    /** 纬度 */
    private BigDecimal latitude;

    /** 经度 */
    private BigDecimal longitude;

    /** 状态（0正常 1停用） */
    @Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    private String status;

    /** 删除标志 */
    private String delFlag;

    public void setStoreId(Long storeId)
    {
        this.storeId = storeId;
    }

    public Long getStoreId()
    {
        return storeId;
    }

    public void setStoreName(String storeName)
    {
        this.storeName = storeName;
    }

    public String getStoreName()
    {
        return storeName;
    }

    public void setCity(String city)
    {
        this.city = city;
    }

    public String getCity()
    {
        return city;
    }

    public void setAddress(String address)
    {
        this.address = address;
    }

    public String getAddress()
    {
        return address;
    }

    public void setCover(String cover)
    {
        this.cover = cover;
    }

    public String getCover()
    {
        return cover;
    }

    public void setIntro(String intro)
    {
        this.intro = intro;
    }

    public String getIntro()
    {
        return intro;
    }

    public void setContactPhone(String contactPhone)
    {
        this.contactPhone = contactPhone;
    }

    public String getContactPhone()
    {
        return contactPhone;
    }

    public void setSafeCert(String safeCert)
    {
        this.safeCert = safeCert;
    }

    public String getSafeCert()
    {
        return safeCert;
    }

    public void setCertTime(Date certTime)
    {
        this.certTime = certTime;
    }

    public Date getCertTime()
    {
        return certTime;
    }

    public void setCertBy(String certBy)
    {
        this.certBy = certBy;
    }

    public String getCertBy()
    {
        return certBy;
    }

    public void setRating(BigDecimal rating)
    {
        this.rating = rating;
    }

    public BigDecimal getRating()
    {
        return rating;
    }

    public String getOpType()
    {
        return opType;
    }

    public void setOpType(String opType)
    {
        this.opType = opType;
    }

    public BigDecimal getLatitude()
    {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude)
    {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude()
    {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude)
    {
        this.longitude = longitude;
    }

    public void setReviewCount(Integer reviewCount)
    {
        this.reviewCount = reviewCount;
    }

    public Integer getReviewCount()
    {
        return reviewCount;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

}
