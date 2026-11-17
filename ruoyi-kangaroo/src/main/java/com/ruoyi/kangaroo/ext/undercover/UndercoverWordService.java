package com.ruoyi.kangaroo.ext.undercover;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Service
public class UndercoverWordService
{
    @Autowired private KgJdbc db;

    public List<UndercoverEngine.WordPair> activePairs()
    {
        return db.list("select word_id,category,first_word,second_word from kg_undercover_word where enabled=1 order by word_id", KgJdbc.p())
                .stream().map(row -> new UndercoverEngine.WordPair(((Number) row.get("wordId")).longValue(),
                        (String) row.get("category"), (String) row.get("firstWord"), (String) row.get("secondWord")))
                .collect(Collectors.toList());
    }

    public Map<String, Object> list(String keyword, String category, String status, int page, int size)
    {
        if (keyword.length() > 30 || !Arrays.asList("", "travel", "daily").contains(category)
                || !Arrays.asList("", "1", "0").contains(status)) throw new ServiceException("筛选条件无效", 400);
        return db.page("select word_id,category,first_word,second_word,enabled,version,update_by,update_time "
                + "from kg_undercover_word where (:category='' or category=:category) "
                + "and (:status='' or enabled=:status) and (:keyword='' or locate(:keyword,first_word)>0 or locate(:keyword,second_word)>0) order by word_id desc",
                KgJdbc.p("category", category, "status", status, "keyword", keyword.trim()), page, size);
    }

    @Transactional
    public Map<String, Object> save(Long id, Map<String, Object> body, String actor)
    {
        String category = UndercoverEngine.string(body, "category");
        String first = word(body, "firstWord");
        String second = word(body, "secondWord");
        if (!Arrays.asList("travel", "daily").contains(category)) throw new ServiceException("请选择有效词库", 400);
        if (first.equalsIgnoreCase(second)) throw new ServiceException("两个词语需要不同", 400);
        if (!(body.get("enabled") instanceof Boolean)) throw new ServiceException("启用状态无效", 400);
        String[] sorted = {first.toLowerCase(java.util.Locale.ROOT), second.toLowerCase(java.util.Locale.ROOT)};
        Arrays.sort(sorted);
        Map<String, Object> params = KgJdbc.p("id", id, "category", category, "first", first, "second", second,
                "key", String.join("|", sorted), "enabled", Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0, "actor", actor);
        try
        {
            if (id == null)
            {
                id = db.insert("kg_undercover_word", KgJdbc.p("category", category, "first_word", first, "second_word", second,
                        "pair_key", params.get("key"), "enabled", params.get("enabled"), "update_by", actor));
            }
            else
            {
                params.put("version", UndercoverEngine.integer(body, "version"));
                int updated = db.update("update kg_undercover_word set category=:category,first_word=:first,second_word=:second,"
                        + "pair_key=:key,enabled=:enabled,version=version+1,update_by=:actor,update_time=now() where word_id=:id and version=:version", params);
                if (updated == 0) throw new ServiceException("词条已被其他管理员更新，请刷新后重试", 409);
            }
        }
        catch (DuplicateKeyException e) { throw new ServiceException("该词库已存在这组词语，请编辑已有词条", 409); }
        return db.must("select word_id,category,first_word,second_word,enabled,version,update_by,update_time from kg_undercover_word where word_id=:id",
                KgJdbc.p("id", id), "词条不存在");
    }

    private String word(Map<String, Object> body, String field)
    {
        String value = Normalizer.normalize(UndercoverEngine.string(body, field).trim(), Normalizer.Form.NFKC);
        if (!value.matches("[\\p{L}\\p{N}]{1,12}")) throw new ServiceException("词语需为1至12个文字或数字，不含空格和标点", 400);
        return value;
    }
}
