package pl.edu.agh.nlp.spark.algorithms.classification;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.mllib.classification.NaiveBayes;
import org.apache.spark.mllib.classification.NaiveBayesModel;
import org.apache.spark.mllib.feature.HashingTF;
import org.apache.spark.mllib.feature.IDF;
import org.apache.spark.mllib.feature.IDFModel;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.clearspring.analytics.util.Lists;

import pl.edu.agh.nlp.exceptions.AbsentModelException;
import pl.edu.agh.nlp.model.entities.Article;
import pl.edu.agh.nlp.model.entities.Article.Category;
import pl.edu.agh.nlp.spark.jdbc.ArticlesReader;
import pl.edu.agh.nlp.utils.Tokenizer;
import scala.Tuple2;

@Service
public class SparkClassification implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 92592979711690198L;
	private static final Logger logger = Logger.getLogger(SparkClassification.class);
	private final static double[] splitTable = { 0.6, 0.2, 0.2 };

	@Autowired
	private Tokenizer tokenizer;
	@Autowired
	private ArticlesReader articlesReader;

	private static final HashingTF hashingTF = new HashingTF(2000000);

	private NaiveBayesModel model;
	private IDFModel idfModel;

	public void buildModel() {
		long time1 = System.currentTimeMillis();
		JavaRDD<Article> data = loadAndPrepareData();
		long time2 = System.currentTimeMillis();
		long buildTime = (time2 - time1);
		logger.info("Time of loading and preparing data: " + buildTime + "ms");

		time1 = System.currentTimeMillis();
		// Budowa modelu idf
		idfModel = builidIDFModel(data);
		// Zrzutowanie publikacji na wektory
		JavaRDD<LabeledPoint> labeledPoints = data.map(a -> new LabeledPoint(a.getCategory().getValue(),
				idfModel.transform(hashingTF.transform(tokenizer.tokenize(a.getText())))));
		time2 = System.currentTimeMillis();
		buildTime = (time2 - time1);
		logger.info("Time of building TFIDF model: " + buildTime + "ms");

		// Dzielimy dane na zbior treningowy oraz testowy
		JavaRDD<LabeledPoint>[] splits = labeledPoints.randomSplit(splitTable);
		JavaRDD<LabeledPoint> training = splits[0];
		JavaRDD<LabeledPoint> test = splits[1];
		JavaRDD<LabeledPoint> validation = splits[2];

		// Walidacja parametrow
		double bestLambda = validateModels(training, validation);

		time1 = System.currentTimeMillis();
		// Budowa modelu
		model = NaiveBayes.train(training.rdd(), bestLambda);
		time2 = System.currentTimeMillis();
		buildTime = (time2 - time1);
		logger.info("Time of building Naive Bayes model: " + buildTime + "ms");

		evaluateModel(model, test);
	}

	private JavaRDD<Article> loadAndPrepareData() {
		// Wczytujemy artukuly z bazy danych
		JavaRDD<Article> data = articlesReader.readArticlesToRDD();
		// Filtrujemy tylko te z tekstem i kategoria
		data = data.filter(a -> a.getText() != null && !a.getText().isEmpty()).filter(a -> a.getCategory() != null);
		// Obliczamy dzial o najmniejszej liczbie reprezentantow
		final Long classSize = data.keyBy(p -> p.getCategory()).countByKey().values().stream().mapToLong(p -> (long) p).min().getAsLong();
		// Wybieramy z artykulow po rowno z kazdej grupy
		return data.groupBy(p -> p.getCategory()).map(t -> Lists.newArrayList(t._2).subList(0, classSize.intValue())).flatMap(f -> f);
	}

	private IDFModel builidIDFModel(JavaRDD<Article> data) {
		JavaRDD<List<String>> javaRdd = data.map(r -> tokenizer.tokenize(r.getText())).filter(a -> !a.isEmpty());
		JavaRDD<Vector> tfData = hashingTF.transform(javaRdd);
		return new IDF().fit(tfData);
	}

	private double validateModels(JavaRDD<LabeledPoint> training, JavaRDD<LabeledPoint> validation) {
		List<Double> lambdas = Arrays.asList(0.00001, 0.0001, 0.001, 0.01, 0.1, 1.0, 10.0, 50.0);
		double bestValidationEffectiveness = 0.0;
		double bestLambda = -1.0;

		for (double lambda : lambdas) {
			NaiveBayesModel validatedModel = NaiveBayes.train(training.rdd(), lambda);
			double validationEffectiveness = evaluateModel(validatedModel, validation);
			logger.info("Effectiveness  (validation) = " + validationEffectiveness + " for the model trained with lambda = " + lambda);

			if (validationEffectiveness > bestValidationEffectiveness) {
				bestValidationEffectiveness = validationEffectiveness;
				bestLambda = lambda;
			}
		}
		logger.info("The best model was trained with lambda = " + bestLambda);
		return bestLambda;
	}

	private double evaluateModel(NaiveBayesModel validatedModel, JavaRDD<LabeledPoint> test) {
		// Ewaluacja modelu
		logger.info("Start evaluating model");
		JavaPairRDD<Double, Double> predictionAndLabel = test
				.mapToPair(p -> new Tuple2<Double, Double>(validatedModel.predict(p.features()), p.label()));
		long accuracy = predictionAndLabel.filter(pl -> {
			return pl._1().equals(pl._2());
		}).count();
		double effectiveness = accuracy / (double) test.count();
		logger.info("Effectiveness: " + effectiveness);
		return effectiveness;
	}

	public Category predictCategory(String text) throws AbsentModelException {
		if (model != null && idfModel != null)
			return Category.fromInt((int) model.predict(idfModel.transform(hashingTF.transform(tokenizer.tokenize(text)))));
		else
			throw new AbsentModelException();
	}

}