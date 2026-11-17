package com.ruoyi.kangaroo.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 岗位 对象 kg_job
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgJob extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 岗位ID */
    @Excel(name = "岗位ID")
    private Long jobId;

    /** 展示顺序：数值越小越靠前；同一筛选范围内有效紧急招募优先。 */
    @Excel(name = "展示顺序")
    private Integer sortNo;
    private Integer urgentLevel;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date urgentDeadline;
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer value) { sortNo = value; }
    public Integer getUrgentLevel() { return urgentLevel; }
    public void setUrgentLevel(Integer value) { urgentLevel = value; }
    public Date getUrgentDeadline() { return urgentDeadline; }
    public void setUrgentDeadline(Date value) { urgentDeadline = value; }

    /** 门店ID */
    @Excel(name = "门店ID")
    private Long storeId;

    /** 实际招聘门店；为空表示发布门店自己招聘。报名和权限仍归 storeId。 */
    private Long recruitStoreId;
    private boolean recruitStoreProvided;
    private String recruitStoreName;
    private String recruitSafeCert;
    private String recruitStoreCover;
    public Long getRecruitStoreId() { return recruitStoreId; }
    public void setRecruitStoreId(Long value) { recruitStoreId = value; recruitStoreProvided = true; }
    @JsonIgnore public boolean isRecruitStoreProvided() { return recruitStoreProvided; }
    @JsonIgnore public Long getDisplayStoreId() { return recruitStoreId == null ? storeId : recruitStoreId; }
    public String getRecruitStoreName() { return recruitStoreName; }
    public void setRecruitStoreName(String value) { recruitStoreName = value; }
    @JsonIgnore public String getRecruitSafeCert() { return recruitSafeCert; }
    public void setRecruitSafeCert(String value) { recruitSafeCert = value; }
    @JsonIgnore public String getRecruitStoreCover() { return recruitStoreCover; }
    public void setRecruitStoreCover(String value) { recruitStoreCover = value; }

    /** 岗位分类（volunteer/parttime/fulltime/online） */
    @Excel(name = "岗位分类", readConverterExp = "volunteer=换工换宿,parttime=兼职旅行,fulltime=全职旅行,online=在线兼职")
    private String jobType;

    /** 换工类型（字典 kg_volunteer_kind，如 daily 日常换工 / photo 摄影换工）；只对换工岗位有意义，其余岗位为空串 */
    @Excel(name = "换工类型", dictType = "kg_volunteer_kind")
    private String workKind;

    public String getWorkKind() { return workKind; }
    public void setWorkKind(String workKind) { this.workKind = workKind; }

    /**
     * 岗位筛选条件（V48，取值与适用范围见 JobFilterFields）：门店选填，'' = 没填。
     * 性别偏好只对换工换宿（'' 不限 / F 女 / M 男）；休息排班只对线下岗位（1-1 做一休一 … 6-1 做六休一）；
     * 住宿房型只在包住时有意义（single 单人间 / double 双人间 / triple 三人间 / multi 多人间）
     */
    @Excel(name = "性别偏好", readConverterExp = "F=女,M=男")
    private String genderPref;
    /** 可选的男女招募计划。两项都为空时沿用总人数和性别偏好。 */
    private Integer genderMaleCount;
    private Integer genderFemaleCount;
    private Boolean genderCountsEnabled;
    public Integer getGenderMaleCount() { return genderMaleCount; }
    public void setGenderMaleCount(Integer value) { genderMaleCount = value; }
    public Integer getGenderFemaleCount() { return genderFemaleCount; }
    public void setGenderFemaleCount(Integer value) { genderFemaleCount = value; }
    public Boolean getGenderCountsEnabled() { return genderCountsEnabled; }
    public void setGenderCountsEnabled(Boolean value) { genderCountsEnabled = value; }


    @Excel(name = "休息排班", readConverterExp = "1-1=做一休一,4-3=做四休三,2-1=做二休一,5-2=做五休二,3-2=做三休二,3-1=做三休一,6-1=做六休一")
    private String restMode;

    @Excel(name = "住宿条件", readConverterExp = "single=单人间,double=双人间,triple=三人间,multi=多人间")
    private String roomType;

    public String getGenderPref() { return genderPref; }
    public void setGenderPref(String genderPref) { this.genderPref = genderPref; }
    public String getRestMode() { return restMode; }
    public void setRestMode(String restMode) { this.restMode = restMode; }
    public String getRoomType() { return roomType; }
    public void setRoomType(String roomType) { this.roomType = roomType; }

    /** 岗位标题 */
    @Excel(name = "岗位标题")
    private String title;

    /** 封面图 */
    private String cover;

    /**
     * 岗位照片（V49，kg_job.images）：JSON 数组，平台上传的 /profile/upload/… 路径，最多 9 张；'' = 没传。
     * 写入前由 JobImages.parse 校验；保存照片时 cover 跟着改成第一张（见 KgJobServiceImpl.applyImages / KgStaffJobService.images）
     */
    private String images;

    /** 门店封面（查询时 join kg_store 带出，只读）：岗位没有照片、也没有封面时用它 */
    private String storeCover;

    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }

    /**
     * 招募表单新字段（V66，规则见 JobExtras）：餐补金额 + 单位（day / month）、宠物（'' 没填 / none / yes）、宠物照片和住宿照片（JSON 数组，同 images）、住宿视频 + 封面。
     * 为 null 表示没带（老版本表单）或老岗位没填，写库时保持原值
     */
    private String mealAllowance;
    private String mealAllowanceUnit;
    private String petStatus;
    private String petImages;
    private String lodgingImages;
    private String lodgingVideo;
    private String lodgingVideoCover;

    public String getMealAllowance() { return mealAllowance; }
    public void setMealAllowance(String mealAllowance) { this.mealAllowance = mealAllowance; }
    public String getMealAllowanceUnit() { return mealAllowanceUnit; }
    public void setMealAllowanceUnit(String mealAllowanceUnit) { this.mealAllowanceUnit = mealAllowanceUnit; }
    public String getPetStatus() { return petStatus; }
    public void setPetStatus(String petStatus) { this.petStatus = petStatus; }
    public String getPetImages() { return petImages; }
    public void setPetImages(String petImages) { this.petImages = petImages; }
    public String getLodgingImages() { return lodgingImages; }
    public void setLodgingImages(String lodgingImages) { this.lodgingImages = lodgingImages; }
    public String getLodgingVideo() { return lodgingVideo; }
    public void setLodgingVideo(String lodgingVideo) { this.lodgingVideo = lodgingVideo; }
    public String getLodgingVideoCover() { return lodgingVideoCover; }
    public void setLodgingVideoCover(String lodgingVideoCover) { this.lodgingVideoCover = lodgingVideoCover; }
    public String getStoreCover() { return storeCover; }
    public void setStoreCover(String storeCover) { this.storeCover = storeCover; }

    /** 城市 */
    @Excel(name = "城市")
    private String city;

    /** 工作地点 */
    @Excel(name = "工作地点")
    private String address;

    /**
     * 工作地点的地图坐标（V42，GCJ-02 即腾讯地图坐标，保留 6 位小数）；两个都为空 = 没选过点。
     * 门店在小程序里地图选点时写入；后台整表单保存时是否保留由 KgJobServiceImpl.applyLocation 决定
     */
    private BigDecimal latitude;

    private BigDecimal longitude;

    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }

    /** 底薪计薪方式（hourly/daily/weekly/monthly/piece/none） */
    @Excel(name = "计薪方式", readConverterExp = "hourly=时薪,daily=日薪,weekly=周薪,monthly=月薪,piece=计件,none=无薪资")
    private String salaryType;

    /** 薪资下限（元） */
    @Excel(name = "薪资下限")
    private BigDecimal salaryMin;

    /** 薪资上限（元） */
    @Excel(name = "薪资上限")
    private BigDecimal salaryMax;

    /** 薪资单位 */
    @Excel(name = "薪资单位")
    private String salaryUnit;

    @Excel(name = "奖金规则")
    private String bonusDesc;

    @Excel(name = "提成规则")
    private String commissionDesc;

    public String getBonusDesc() { return bonusDesc; }
    public void setBonusDesc(String bonusDesc) { this.bonusDesc = bonusDesc; }
    public String getCommissionDesc() { return commissionDesc; }
    public void setCommissionDesc(String commissionDesc) { this.commissionDesc = commissionDesc; }

    /** 结算方式说明 */
    private String settleDesc;

    /** 可开始日期 */
    @Excel(name = "可开始日期", width = 30, dateFormat = "yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;

    /** 最短停留天数 */
    @Excel(name = "最短停留天数")
    private Integer minDays;

    /** 每日工时 */
    private String dailyHours;

    /** 排班方式 */
    private String shiftDesc;

    /** 食宿（all/room/meal/part/none） */
    @Excel(name = "食宿", readConverterExp = "all=包食宿,room=包住宿,meal=包三餐,part=包部分,none=不包")
    private String boardType;

    /** 食宿说明 */
    private String boardDesc;

    /** 是否含保险（0含 1不含） */
    @Excel(name = "含保险", readConverterExp = "0=含保险,1=不含保险")
    private String insuranceFlag;

    /** 做满一个月有红包（0有 1没有，与 insurance_flag 同一约定；V44） */
    @Excel(name = "满月红包", readConverterExp = "0=有,1=没有")
    private String monthRedpacket;

    /** 招聘名额 */
    @Excel(name = "招聘名额")
    private Integer headcount;

    /** 已录用人数 */
    @Excel(name = "已录用")
    private Integer hiredCount;

    /** 有效报名人数（含已到岗），从报名记录汇总，只读。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Integer reservedCount;

    /** 累计报名人数 */
    @Excel(name = "累计报名")
    private Integer appliedCount;

    /** 标签，逗号分隔 */
    @Excel(name = "标签")
    private String tags;

    /** 工作内容 */
    private String content;

    /** 任职要求 */
    private String requirement;

    /** 岗位状态（draft/published/paused/closed） */
    @Excel(name = "岗位状态", readConverterExp = "draft=草稿,published=招聘中,paused=暂停,closed=已关闭")
    private String status;

    /** 来源：admin 后台录入 / staff 门店员工提交（平台审核后上架）；只读，不参与新增与修改 */
    private String source;

    /** 发布时间 */
    @Excel(name = "发布时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date publishTime;

    /** 关闭时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date closeTime;

    /** 排班时段（如 10:00-18:00） */
    @Excel(name = "排班时段")
    private String shiftTime;

    /** 培训项目名称 */
    @Excel(name = "培训项目")
    private String trainDesc;

    /** 试用期描述 */
    @Excel(name = "试用期")
    private String probation;

    /** 福利，英文逗号分隔 */
    @Excel(name = "福利")
    private String benefits;

    /** 交付日期描述（如 2天内） */
    @Excel(name = "交付日期")
    private String deliverDesc;

    /** 预计工时（如 2-3小时） */
    @Excel(name = "预计工时")
    private String estHours;

    /** 是否远程岗位（0是 1否） */
    @Excel(name = "远程", readConverterExp = "0=远程,1=非远程")
    private String remoteFlag;

    /** 发单方名称（在线兼职列表展示用） */
    @Excel(name = "发单方")
    private String publisherName;

    /** 删除标志 */
    private String delFlag;

    /** 门店名称（关联查询字段，非本表列） */
    @Excel(name = "门店名称")
    private String storeName;

    /** 安心商家认证（关联查询字段，非本表列） */
    @Excel(name = "安心商家认证", readConverterExp = "0=已认证,1=未认证")
    private String safeCert;

    public void setJobId(Long jobId)
    {
        this.jobId = jobId;
    }

    public Long getJobId()
    {
        return jobId;
    }

    public void setStoreId(Long storeId)
    {
        this.storeId = storeId;
    }

    public Long getStoreId()
    {
        return storeId;
    }

    public void setJobType(String jobType)
    {
        this.jobType = jobType;
    }

    public String getJobType()
    {
        return jobType;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getTitle()
    {
        return title;
    }

    public void setCover(String cover)
    {
        this.cover = cover;
    }

    public String getCover()
    {
        return cover;
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

    public void setSalaryType(String salaryType)
    {
        this.salaryType = salaryType;
    }

    public String getSalaryType()
    {
        return salaryType;
    }

    public void setSalaryMin(BigDecimal salaryMin)
    {
        this.salaryMin = salaryMin;
    }

    public BigDecimal getSalaryMin()
    {
        return salaryMin;
    }

    public void setSalaryMax(BigDecimal salaryMax)
    {
        this.salaryMax = salaryMax;
    }

    public BigDecimal getSalaryMax()
    {
        return salaryMax;
    }

    public void setSalaryUnit(String salaryUnit)
    {
        this.salaryUnit = salaryUnit;
    }

    public String getSalaryUnit()
    {
        return salaryUnit;
    }

    public void setSettleDesc(String settleDesc)
    {
        this.settleDesc = settleDesc;
    }

    public String getSettleDesc()
    {
        return settleDesc;
    }

    public void setStartDate(Date startDate)
    {
        this.startDate = startDate;
    }

    public Date getStartDate()
    {
        return startDate;
    }

    public void setMinDays(Integer minDays)
    {
        this.minDays = minDays;
    }

    public Integer getMinDays()
    {
        return minDays;
    }

    public void setDailyHours(String dailyHours)
    {
        this.dailyHours = dailyHours;
    }

    public String getDailyHours()
    {
        return dailyHours;
    }

    public void setShiftDesc(String shiftDesc)
    {
        this.shiftDesc = shiftDesc;
    }

    public String getShiftDesc()
    {
        return shiftDesc;
    }

    public void setBoardType(String boardType)
    {
        this.boardType = boardType;
    }

    public String getBoardType()
    {
        return boardType;
    }

    public void setBoardDesc(String boardDesc)
    {
        this.boardDesc = boardDesc;
    }

    public String getBoardDesc()
    {
        return boardDesc;
    }

    public void setInsuranceFlag(String insuranceFlag)
    {
        this.insuranceFlag = insuranceFlag;
    }

    public String getInsuranceFlag()
    {
        return insuranceFlag;
    }

    public void setMonthRedpacket(String monthRedpacket)
    {
        this.monthRedpacket = monthRedpacket;
    }

    public String getMonthRedpacket()
    {
        return monthRedpacket;
    }

    public void setHeadcount(Integer headcount)
    {
        this.headcount = headcount;
    }

    public Integer getHeadcount()
    {
        return headcount;
    }

    public void setHiredCount(Integer hiredCount)
    {
        this.hiredCount = hiredCount;
    }

    public Integer getHiredCount()
    {
        return hiredCount;
    }

    public Integer getReservedCount()
    {
        return reservedCount;
    }

    public void setReservedCount(Integer reservedCount)
    {
        this.reservedCount = reservedCount;
    }

    /** 换宿岗位提交成功即占位；其他分类沿用录用 / 接单口径。 */
    public int recruitmentOccupiedCount()
    {
        Integer count = "volunteer".equals(jobType) && reservedCount != null ? reservedCount : hiredCount;
        return count == null ? 0 : Math.max(0, count);
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public Integer getRemainingCount()
    {
        return headcount == null ? null : Math.max(0, headcount - recruitmentOccupiedCount());
    }

    public void setAppliedCount(Integer appliedCount)
    {
        this.appliedCount = appliedCount;
    }

    public Integer getAppliedCount()
    {
        return appliedCount;
    }

    public void setTags(String tags)
    {
        this.tags = tags;
    }

    public String getTags()
    {
        return tags;
    }

    public void setContent(String content)
    {
        this.content = content;
    }

    public String getContent()
    {
        return content;
    }

    public void setRequirement(String requirement)
    {
        this.requirement = requirement;
    }

    public String getRequirement()
    {
        return requirement;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStatus()
    {
        return status;
    }

    public void setPublishTime(Date publishTime)
    {
        this.publishTime = publishTime;
    }

    public Date getPublishTime()
    {
        return publishTime;
    }

    public void setCloseTime(Date closeTime)
    {
        this.closeTime = closeTime;
    }

    public Date getCloseTime()
    {
        return closeTime;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setStoreName(String storeName)
    {
        this.storeName = storeName;
    }

    public String getStoreName()
    {
        return storeName;
    }

    public void setSafeCert(String safeCert)
    {
        this.safeCert = safeCert;
    }

    public String getSafeCert()
    {
        return safeCert;
    }

    public void setShiftTime(String shiftTime)
    {
        this.shiftTime = shiftTime;
    }

    public String getShiftTime()
    {
        return shiftTime;
    }

    public void setTrainDesc(String trainDesc)
    {
        this.trainDesc = trainDesc;
    }

    public String getTrainDesc()
    {
        return trainDesc;
    }

    public void setProbation(String probation)
    {
        this.probation = probation;
    }

    public String getProbation()
    {
        return probation;
    }

    public void setBenefits(String benefits)
    {
        this.benefits = benefits;
    }

    public String getBenefits()
    {
        return benefits;
    }

    public void setDeliverDesc(String deliverDesc)
    {
        this.deliverDesc = deliverDesc;
    }

    public String getDeliverDesc()
    {
        return deliverDesc;
    }

    public void setEstHours(String estHours)
    {
        this.estHours = estHours;
    }

    public String getEstHours()
    {
        return estHours;
    }

    public void setRemoteFlag(String remoteFlag)
    {
        this.remoteFlag = remoteFlag;
    }

    public String getRemoteFlag()
    {
        return remoteFlag;
    }

    public void setPublisherName(String publisherName)
    {
        this.publisherName = publisherName;
    }

    public String getPublisherName()
    {
        return publisherName;
    }


    public String getSource()
    {
        return source;
    }

    public void setSource(String source)
    {
        this.source = source;
    }
}
