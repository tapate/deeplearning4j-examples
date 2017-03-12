package org.deeplearning4j.examples.convolution.regression;


import org.apache.commons.io.FileUtils;
import org.datavec.api.records.reader.SequenceRecordReader;
import org.datavec.api.records.reader.impl.csv.CSVSequenceRecordReader;
import org.datavec.api.split.NumberedFileInputSplit;
import org.deeplearning4j.datasets.datavec.SequenceRecordReaderDataSetIterator;
import org.deeplearning4j.eval.RegressionEvaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.ui.RefineryUtilities;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.NormalizerMinMaxScaler;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;


/**
 * This example demonstrates one-step-ahead time series forecasting using a 1D temporal
 * convolutional network with global pooling. It is cannibalized from MultiTimestepRegressionExample.java,
 * which demonstrates time series forecasting using an LSTM.
 *
 * Compare this with TemporalConvolutionRegressionExample.java, which uses the same 1D
 * convolutional layer but with ConvolutionMode.Same and a subsequent 1D max pooling
 * layer, also using the "Same" mode. That model outputs one prediction per timestep,
 * the same as the LSTM example, and hence requires an RnnOutputLayer. Here we use
 * a mean global pooling layer to collapse the time axis into a fixed-size feature
 * vector. Thus, we have only a single target (the next timestep), and we use a
 * normal OutputLayer.
 *
 * The original example was inspired by Jason Brownlee's regression examples for Keras found at
 *
 * http://machinelearningmastery.com/time-series-prediction-lstm-recurrent-neural-networks-python-keras/
 */
public class TemporalConvolutionRegressionExampleWithGlobalPooling {
    private static final Logger LOGGER = LoggerFactory.getLogger(TemporalConvolutionRegressionExampleWithGlobalPooling.class);

    private static File baseDir = new File("dl4j-examples/src/main/resources/rnnRegression");
    private static File baseTrainDir = new File(baseDir, "multiTimestepTrain");
    private static File featuresDirTrain = new File(baseTrainDir, "features");
    private static File labelsDirTrain = new File(baseTrainDir, "labels");
    private static File baseTestDir = new File(baseDir, "multiTimestepTest");
    private static File featuresDirTest = new File(baseTestDir, "features");
    private static File labelsDirTest = new File(baseTestDir, "labels");

    private static int numOfVariables = 0;  // in csv.

    public static void main(String[] args) throws Exception {

        //Set number of examples for training, testing, and time steps
        int trainSize = 100;
        int testSize = 20;
        int numberOfTimesteps = 20;

        //Prepare multi time step data, see method comments for more info
        List<String> rawStrings = prepareTrainAndTest(trainSize, testSize, numberOfTimesteps);

        //Make sure miniBatchSize is divisable by trainSize and testSize,
        //as rnnTimeStep will not accept different sized examples
        int miniBatchSize = 10;

        // ----- Load the training data -----
        SequenceRecordReader trainFeatures = new CSVSequenceRecordReader();
        trainFeatures.initialize(new NumberedFileInputSplit(featuresDirTrain.getAbsolutePath() + "/train_%d.csv", 0, trainSize - 1));
        SequenceRecordReader trainLabels = new CSVSequenceRecordReader();
        trainLabels.initialize(new NumberedFileInputSplit(labelsDirTrain.getAbsolutePath() + "/train_%d.csv", 0, trainSize - 1));

        DataSetIterator trainDataIter = new SequenceRecordReaderDataSetIterator(trainFeatures, trainLabels, miniBatchSize, -1, true, SequenceRecordReaderDataSetIterator.AlignmentMode.ALIGN_END);

        //Normalize the training data
        NormalizerMinMaxScaler normalizer = new NormalizerMinMaxScaler(0, 1);
        normalizer.fitLabel(true);
        normalizer.fit(trainDataIter);              //Collect training data statistics
        trainDataIter.reset();


        // ----- Load the test data -----
        //Same process as for the training data.
        SequenceRecordReader testFeatures = new CSVSequenceRecordReader();
        testFeatures.initialize(new NumberedFileInputSplit(featuresDirTest.getAbsolutePath() + "/test_%d.csv", trainSize, trainSize + testSize - 1));
        SequenceRecordReader testLabels = new CSVSequenceRecordReader();
        testLabels.initialize(new NumberedFileInputSplit(labelsDirTest.getAbsolutePath() + "/test_%d.csv", trainSize, trainSize + testSize - 1));

        DataSetIterator testDataIter = new SequenceRecordReaderDataSetIterator(testFeatures, testLabels, miniBatchSize, -1, true, SequenceRecordReaderDataSetIterator.AlignmentMode.ALIGN_END);

        trainDataIter.setPreProcessor(normalizer);
        testDataIter.setPreProcessor(normalizer);


        // ----- Configure the network -----
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
            .seed(140)
            .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
            .iterations(1)
            .weightInit(WeightInit.XAVIER)
            .updater(Updater.NESTEROVS).momentum(0.9)
            .learningRate(0.15)
            .list()
            /*
             * This approach treats a multivariate time series with L timesteps and
             * P variables as an L x 1 x P image (L rows high, 1 column wide, P
             * channels deep). The kernel should be H<L pixels high and W=1 pixels
             * wide.
             *
             * The stride width is 1. The stride height can be whatever we want.
             *
             * To directly parallel an RNN (which has one output for every input), we
             * need to use ConvolutionMode.Same.
             */
            .layer(0, new Convolution1DLayer.Builder()
                .kernelSize(5)
                .stride(1)
                .convolutionMode(ConvolutionMode.Truncate)
                .activation(Activation.RELU)
                .nIn(numOfVariables)
                .nOut(10)
                .build())
            .layer(1, new GlobalPoolingLayer.Builder(PoolingType.AVG)
                .poolingDimensions(2)       // i.e., time / sequence length
                .collapseDimensions(true)
                .build())
            /* Global pooling along the temporal axis collapses the sequence
             * into a fixed-size feature vector, so we can use a normal output
             * layer here.
             *
             * NOTE: this seems to give a lower MSE for one-step prediction
             * but if we use it forecast further into the future (so that
             * we use previous predicted values as inputs), the resulting
             * predictions don't look as good. The average global pooling
             * seems to smooth things out a little. Perhaps a "get last time
             * step" pooling layer might work a little better.
             */
            .layer(2, new OutputLayer.Builder(LossFunctions.LossFunction.MSE)
                .nOut(2)
                .activation(Activation.IDENTITY)
                .build())
            .setInputType(new InputType.InputTypeRecurrent(numOfVariables))
            .build();

        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();

        net.setListeners(new ScoreIterationListener(20));

        // ----- Train the network, evaluating the test set performance at each epoch -----
        int nEpochs = 100;

        for (int i = 0; i < nEpochs; i++) {
            while (trainDataIter.hasNext()) {
                DataSet t = trainDataIter.next();
                INDArray features = t.getFeatureMatrix();
                INDArray labels = t.getLabels();
                // HACK: our targets are a sequence, but we only want the last target
                labels = labels.get(NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.point(numberOfTimesteps-1));
                net.fit(features, labels);
            }
            trainDataIter.reset();
            LOGGER.info("Epoch " + i + " complete. Time series evaluation:");

            //Run regression evaluation on our two column output
            RegressionEvaluation evaluation = new RegressionEvaluation(numOfVariables);

            while (testDataIter.hasNext()) {
                DataSet t = testDataIter.next();
                INDArray features = t.getFeatureMatrix();
                INDArray labels = t.getLabels();
                // HACK: our targets are a sequence, but we only want the last target
                labels = labels.get(NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.point(numberOfTimesteps-1));
                INDArray predicted = net.output(features, true);
                evaluation.eval(labels, predicted);
            }

            System.out.println(evaluation.stats());

            testDataIter.reset();
        }

        /**
         * All code below this point is only necessary for plotting
         */
        INDArray f = null;
        while (trainDataIter.hasNext()) {
            DataSet t = trainDataIter.next();
            f = t.getFeatureMatrix();
        }
        trainDataIter.reset();

        DataSet t = testDataIter.next();
        f = Nd4j.concat(2, f, t.getFeatureMatrix());
        INDArray predicted = Nd4j.zeros(f.size(0), numOfVariables, numberOfTimesteps);
        for (int i = numberOfTimesteps; i < f.size(2); i++) {
            INDArray pred = net.rnnTimeStep(f.get(NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.interval(i-numberOfTimesteps, i)));
            predicted.get(NDArrayIndex.all(), NDArrayIndex.all(), NDArrayIndex.point(i-numberOfTimesteps))
                      .assign(pred.reshape(pred.size(0), numOfVariables, 1));
        }

        normalizer.revertLabels(predicted);

        //Convert raw string data to IndArrays for plotting
        INDArray trainArray = createIndArrayFromStringList(rawStrings, 0, trainSize);
        INDArray testArray = createIndArrayFromStringList(rawStrings, trainSize, testSize);

        //Create plot with out data
        XYSeriesCollection c = new XYSeriesCollection();
        createSeries(c, trainArray, 0, "Train data");
        createSeries(c, testArray, trainSize - 1, "Actual test data");
        createSeries(c, predicted, trainSize - 1, "Predicted test data");

        plotDataset(c);

        LOGGER.info("----- Example Complete -----");
    }


    /**
     * Creates an IndArray from a list of strings
     * Used for plotting purposes
     */
    private static INDArray createIndArrayFromStringList(List<String> rawStrings, int startIndex, int length) {
        List<String> stringList = rawStrings.subList(startIndex, startIndex + length);

        double[][] primitives = new double[numOfVariables][stringList.size()];
        for (int i = 0; i < stringList.size(); i++) {
            String[] vals = stringList.get(i).split(",");
            for (int j = 0; j < vals.length; j++) {
                primitives[j][i] = Double.valueOf(vals[j]);
            }
        }

        return Nd4j.create(new int[]{1, length}, primitives);
    }

    /**
     * Used to create the different time series for ploting purposes
     */
    private static XYSeriesCollection createSeries(XYSeriesCollection seriesCollection, INDArray data, int offset, String name) {
        int nRows = data.shape()[2];
        boolean predicted = name.startsWith("Predicted");
        int repeat = predicted ? data.shape()[1] : data.shape()[0];

        for (int j = 0; j < repeat; j++) {
            XYSeries series = new XYSeries(name + j);
            for (int i = 0; i < nRows; i++) {
                if (predicted)
                    series.add(i + offset, data.slice(0).slice(j).getDouble(i));
                else
                    series.add(i + offset, data.slice(j).getDouble(i));
            }
            seriesCollection.addSeries(series);
        }

        return seriesCollection;
    }

    /**
     * Generate an xy plot of the datasets provided.
     */
    private static void plotDataset(XYSeriesCollection c) {

        String title = "Regression example";
        String xAxisLabel = "Timestep";
        String yAxisLabel = "Number of passengers";
        PlotOrientation orientation = PlotOrientation.VERTICAL;
        boolean legend = true;
        boolean tooltips = false;
        boolean urls = false;
        JFreeChart chart = ChartFactory.createXYLineChart(title, xAxisLabel, yAxisLabel, c, orientation, legend, tooltips, urls);

        // get a reference to the plot for further customisation...
        final XYPlot plot = chart.getXYPlot();

        // Auto zoom to fit time series in initial window
        final NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setAutoRange(true);

        JPanel panel = new ChartPanel(chart);

        JFrame f = new JFrame();
        f.add(panel);
        f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        f.pack();
        f.setTitle("Training Data");

        RefineryUtilities.centerFrameOnScreen(f);
        f.setVisible(true);
    }

    /**
     * This method shows how you based on a CSV file can preprocess your data the structure expected for a
     * multi time step problem. This examples uses a single column CSV as input, but the example should be easy to modify
     * for use with a multi column input as well.
     *
     * @return
     * @throws IOException
     */
    private static List<String> prepareTrainAndTest(int trainSize, int testSize, int numberOfTimesteps) throws IOException {
        Path rawPath = Paths.get(baseDir.getAbsolutePath() + "/passengers_raw.csv");

        List<String> rawStrings = Files.readAllLines(rawPath, Charset.defaultCharset());
        setNumOfVariables(rawStrings);

        //Remove all files before generating new ones
        FileUtils.cleanDirectory(featuresDirTrain);
        FileUtils.cleanDirectory(labelsDirTrain);
        FileUtils.cleanDirectory(featuresDirTest);
        FileUtils.cleanDirectory(labelsDirTest);

        for (int i = 0; i < trainSize; i++) {
            Path featuresPath = Paths.get(featuresDirTrain.getAbsolutePath() + "/train_" + i + ".csv");
            Path labelsPath = Paths.get(labelsDirTrain + "/train_" + i + ".csv");
            int j;
            for (j = 0; j < numberOfTimesteps; j++) {
                Files.write(featuresPath, rawStrings.get(i + j).concat(System.lineSeparator()).getBytes(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            }
            Files.write(labelsPath, rawStrings.get(i + j).concat(System.lineSeparator()).getBytes(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        }

        for (int i = trainSize; i < testSize + trainSize; i++) {
            Path featuresPath = Paths.get(featuresDirTest + "/test_" + i + ".csv");
            Path labelsPath = Paths.get(labelsDirTest + "/test_" + i + ".csv");
            int j;
            for (j = 0; j < numberOfTimesteps; j++) {
                Files.write(featuresPath, rawStrings.get(i + j).concat(System.lineSeparator()).getBytes(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            }
            Files.write(labelsPath, rawStrings.get(i + j).concat(System.lineSeparator()).getBytes(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        }

        return rawStrings;
    }

    private static void setNumOfVariables(List<String> rawStrings) {
        numOfVariables = rawStrings.get(0).split(",").length;
    }
}
