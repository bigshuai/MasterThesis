package pl.edu.agh.nlp.model.postgresqlDao;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcDaoSupport;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSourceUtils;

import com.google.common.collect.Lists;

import pl.edu.agh.nlp.model.dao.TopicsArticlesDao;
import pl.edu.agh.nlp.model.entities.TopicArticle;

public class TopicsArticlesDaoPostgresql extends NamedParameterJdbcDaoSupport implements TopicsArticlesDao, Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -4871337423797581590L;
	private static final int batchSize = 100000;

	public void insert(final List<TopicArticle> topics) {
		String sql = "insert into topics_articles(articleId, topicId, weight) values (:articleId, :topicId, :weight)";
		List<List<TopicArticle>> batchLists = Lists.partition(topics, batchSize);
		for (List<TopicArticle> batch : batchLists) {
			SqlParameterSource[] params = SqlParameterSourceUtils.createBatch(batch.toArray());
			getNamedParameterJdbcTemplate().batchUpdate(sql, params);
		}
	}

	public List<TopicArticle> findByTopicsByArticleId(final Integer articleId) {
		String sql = "select topicId, articleId, weight from topics_articles where articleId=:articleId";
		Map<String, Object> parameters = Collections.singletonMap("articleId", articleId);
		return getNamedParameterJdbcTemplate().query(sql, parameters, new BeanPropertyRowMapper<TopicArticle>(TopicArticle.class));
	}

	public void deleteAll() {
		String sql = "delete from topics_articles";
		getJdbcTemplate().update(sql);
	}
}
