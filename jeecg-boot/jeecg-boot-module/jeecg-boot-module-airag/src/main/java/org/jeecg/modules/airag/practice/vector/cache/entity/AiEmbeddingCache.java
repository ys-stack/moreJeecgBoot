package org.jeecg.modules.airag.practice.vector.cache.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/*
 * @Author: ys
 * @Date: 2026/7/15 16:51
 * @DESC: MySQL Embedding 持久化缓存
 */
@Data
@Accessors(chain = true)
@TableName("ai_embedding_cache")
public class AiEmbeddingCache implements Serializable {

    private static final long serialVersionUID = 1L;

    /** MyBatis-Plus 自动生成的主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 向量缓存唯一键。
     * 由租户、模型名称、模型版本、维度、归一化版本和文本通过 HMAC-SHA256 生成。
     */
    private String cacheKey;

    /** 数据所属租户；默认租户为 0，禁止跨租户复用私有文档向量。 */
    private String tenantId;

    /** Embedding 模型名称，例如 BAAI/bge-m3。 */
    private String modelName;

    /**
     * 模型业务版本。即使供应商模型名称不变，模型升级或输出发生变化时也应修改该值。
     */
    private String modelVersion;

    /** 文本预处理算法版本；归一化规则变化后修改该值，避免错误复用旧向量。 */
    private String normalizationVersion;

    /** 向量维度，例如 bge-m3 当前配置为 1024。 */
    private Integer dimensions;

    /** float[] 按大端序编码后的二进制数据，空间占用约为维度数乘以 4 字节。 */
    private byte[] vectorData;

    /** vectorData 的 SHA-256，用于识别数据库或序列化过程中的数据损坏。 */
    private String vectorChecksum;

    /** 首次生成该向量缓存的时间。 */
    private Date createTime;

    /** 缓存记录最后一次被改写的时间；内容寻址设计下通常与创建时间相同。 */
    private Date updateTime;

    /** 最近一次从 MySQL 回源命中的时间，可用于离线清理长期冷数据。 */
    private Date lastHitTime;
}
