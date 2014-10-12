package org.deeplearning4j.plot;

import com.google.common.base.Function;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.math3.random.MersenneTwister;
import org.deeplearning4j.berkeley.Pair;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dimensionalityreduction.PCA;
import static org.nd4j.linalg.factory.Nd4j.*;

import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.BooleanIndexing;
import org.nd4j.linalg.indexing.conditions.Conditions;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.indexing.functions.Value;
import org.nd4j.linalg.indexing.functions.Zero;
import org.nd4j.linalg.learning.AdaGrad;

import static org.nd4j.linalg.factory.Nd4j.diag;
import static org.nd4j.linalg.ops.transforms.Transforms.*;

import org.nd4j.linalg.ops.transforms.Transforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;


import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;


import static org.nd4j.linalg.ops.transforms.Transforms.max;

/**
 * Tsne calculation
 * @author Adam Gibson
 */
public class Tsne {

    private int maxIter = 1000;
    private double realMin = 1e-12f;
    private double initialMomentum = 0.5f;
    private double finalMomentum = 0.8f;
    private  double minGain = 1e-2f;
    private double momentum = initialMomentum;
    private int switchMomentumIteration = 100;
    private boolean normalize = true;
    private boolean usePca = false;
    private int stopLyingIteration = 250;
    private double tolerance = 1e-5f;
    private double learningRate = 1e-1f;
    private AdaGrad adaGrad;
    private boolean useAdaGrad = true;
    private double perplexity = 30f;
    private INDArray gains,yIncs;

    private String commandTemplate = "python /tmp/tsne.py --path %s --ndims %d --perplexity %.3f --initialdims %s --labels %s";



    private static ClassPathResource r = new ClassPathResource("/scripts/tsne.py");
    private static ClassPathResource r2 = new ClassPathResource("/scripts/render.py");


    static {
        loadIntoTmp();
    }

    private static void loadIntoTmp() {

        File script = new File("/tmp/tsne.py");


        try {
            List<String> lines = IOUtils.readLines(r.getInputStream());
            FileUtils.writeLines(script, lines);

        } catch (IOException e) {
            throw new IllegalStateException("Unable to load python file");

        }


        File script2 = new File("/tmp/render.py");


        try {
            List<String> lines2 = IOUtils.readLines(r2.getInputStream());
            FileUtils.writeLines(script2, lines2);

        } catch (IOException e) {
            throw new IllegalStateException("Unable to load python file");

        }

    }
    private static Logger log = LoggerFactory.getLogger(Tsne.class);

    public Tsne(
            int maxIter,
            double realMin,
            double initialMomentum,
            double finalMomentum,
            double momentum,
            int switchMomentumIteration,
            boolean normalize,
            boolean usePca,
            int stopLyingIteration,
            double tolerance,double learningRate,boolean useAdaGrad,double perplexity,double minGain) {
        this.tolerance = tolerance;
        this.minGain = minGain;
        this.useAdaGrad = useAdaGrad;
        this.learningRate = learningRate;
        this.stopLyingIteration = stopLyingIteration;
        this.maxIter = maxIter;
        this.realMin = realMin;
        this.normalize = normalize;
        this.initialMomentum = initialMomentum;
        this.usePca = usePca;
        this.finalMomentum = finalMomentum;
        this.momentum = momentum;
        this.switchMomentumIteration = switchMomentumIteration;
        this.perplexity = perplexity;
    }

    /**
     * Computes a gaussian kernel
     * given a vector of squared euclidean distances
     *
     * @param d
     * @param beta
     * @return
     */
    public Pair<INDArray,INDArray> hBeta(INDArray d,double beta) {
        INDArray P =  exp(d.neg().muli(beta));
        INDArray sum = P.sum(Integer.MAX_VALUE);
        INDArray otherSum = d.mul(P).sum(0);
        INDArray H = log(sum)
                .addi(otherSum.muli(beta).divi(sum));

        P.divi(sum);
        return new Pair<>(H,P);
    }




    /**
     * Convert data to probability
     * co-occurrences
     * @param d the data to convert
     * @param u the perplexity of the model
     * @param tolerance the tolerance for convergence
     * @return the probabilities of co-occurrence
     */
    public INDArray d2p(final INDArray d,final double u,final double tolerance) {
        int n = d.rows();
        final INDArray p = zeros(n, n);
        final INDArray beta =  ones(n, 1);
        final double logU = (double) Math.log(u);
        log.info("Calculating probabilities of data similarities..");

        for(int i = 0; i < n; i++) {
            if(i % 500 == 0)
                log.info("Handled " + i + " records");
            final int j = i;
            double betaMin = Float.NEGATIVE_INFINITY;
            double betaMax = Float.POSITIVE_INFINITY;

            INDArray row = d.slice(j).get(NDArrayIndex.interval(1, d.columns()));
            Pair<INDArray,INDArray> pair =  hBeta(row,beta.getDouble(j));

            INDArray hDiff = pair.getFirst().sub(logU);
            int tries = 0;


            //while hdiff > tolerance
            while(BooleanIndexing.and(abs(hDiff), Conditions.greaterThan(tolerance)) && tries < 50) {
                //if hdiff > 0
                if(BooleanIndexing.and(hDiff,Conditions.greaterThan(0))) {
                    betaMin = beta.getDouble(j);
                    if(Double.isInfinite(betaMax))
                        beta.putScalar(j,beta.getDouble(j) * 2);
                    else
                        beta.putScalar(j,(beta.getDouble(j) + betaMax) / 2);
                }
                else {
                    betaMax = beta.getDouble(j);
                    if(Double.isInfinite(betaMin))
                        beta.putScalar(j,beta.getDouble(j) / 2);
                    else
                        beta.putScalar(j,(beta.getDouble(j) + betaMin) / 2);
                }

                pair = hBeta(d.slice(j).get(NDArrayIndex.interval(1, d.columns())),beta.getDouble(j));
                hDiff = pair.getFirst().subi(logU);
                tries++;
            }

            p.slice(j).put(new NDArrayIndex[]{NDArrayIndex.interval(1, d.columns())}, pair.getSecond());
        }


        //set 0 along the diagonal
        doAlongDiagonal(p,new Function<Number, Number>() {
            @Override
            public Number apply(Number input) {
                return 0;
            }
        });

        INDArray pOut = p.add(p.transpose());
        pOut.divi(p.sum(Integer.MAX_VALUE));
        pOut = Transforms.max(pOut, 1e-12f);
        //ensure no nans
        BooleanIndexing.applyWhere(pOut,Conditions.isNan(),new Value(realMin));
        log.info("Mean value of sigma " + sqrt(beta.rdiv(1).mean(Integer.MAX_VALUE)));
        return pOut;

    }






    /**
     *
     * @param X
     * @param nDims
     * @param perplexity
     */
    public  INDArray calculate(INDArray X,int nDims,double perplexity) {
        if(usePca)
            X = PCA.pca(X, Math.min(50,X.columns()),normalize);
            //normalization (don't normalize again after pca)
        else if(normalize) {
            X = X.sub(X.min(Integer.MAX_VALUE));
            X = X.divi(X.max(Integer.MAX_VALUE));
            X = X.subiRowVector(X.mean(0));
        }

        if(nDims > X.columns())
            nDims = X.columns();




        INDArray sumX =  pow(X, 2).sum(1);


        INDArray D = X.mmul(
                X.transpose()).mul(-2)
                .addiRowVector(sumX)
                .transpose()
                .addiRowVector(sumX);


        //output
        //INDArray y = randn(X.rows(),nDims,new MersenneTwister(123)).muli(1e-3f);
        INDArray y = null;
        try {
            y = Nd4j.readTxt("/home/agibsonccc/Desktop/y.txt"," ");
        } catch (IOException e) {
            throw new IllegalStateException("weird");
        }


        INDArray p = d2p(D,perplexity,tolerance);


        //lie for better local minima
        p.muli(4);

        //init adagrad where needed
        if(useAdaGrad) {
            if(adaGrad == null) {
                adaGrad = new AdaGrad(y.shape());
                adaGrad.setMasterStepSize(learningRate);
            }
        }




        for(int i = 0; i < maxIter; i++) {
            step(y,p,i);

            if(i == switchMomentumIteration)
                momentum = finalMomentum;
            if(i == stopLyingIteration)
                p.divi(4);

        }

        return y;
    }


    /* compute the gradient given the current solution, the probabilities and the constant */
    private Pair<Double,INDArray> gradient(INDArray y,INDArray p) {
        INDArray sumY =  pow(y, 2).sum(1);
        if(yIncs == null)
            yIncs =  zeros(y.shape());
        if(gains == null)
            gains = ones(y.shape());


        //Student-t distribution
        //also un normalized q
        INDArray qu = y.mmul(
                y.transpose())
                .muli(-2)
                .addiRowVector(sumY).transpose()
                .addiRowVector(sumY)
                .addi(1).rdivi(1);

        //set diagonal to zero
        doAlongDiagonal(qu,new Zero());



        // normalize to get probabilities
        INDArray  q =  max(qu.div(qu.sum(Integer.MAX_VALUE)), realMin);

        INDArray PQ = p.sub(q);

        INDArray yGrads = Nd4j.create(y.shape());
        for(int i = 0; i < yGrads.columns(); i++) {
            //dY[i,:] = np.sum(np.tile(PQ[:,i] * num[:,i], (no_dims, 1)).T * (Y[i,:] - Y), 0)
            INDArray toTile = PQ.getRow(i).mul(qu.getRow(i));
            INDArray tiledTranspose = Nd4j.tile(toTile, new int[]{y.columns(), 1}).transpose();
            INDArray yBroadCast = y.getColumn(i).broadcast(y.shape());
            INDArray mul = tiledTranspose.mul(yBroadCast).sub(y);
            INDArray sum1 = mul.sum(0);
            yGrads.putRow(i, sum1);
        }

        gains = gains.add(.2f)
                .mul(yGrads.cond(Conditions.greaterThan(0)).neq(yIncs.cond(Conditions.greaterThan(0))))
                .add(gains.mul(0.8f).mul(yGrads.cond(Conditions.greaterThan(0)).eq(yIncs.cond(Conditions.greaterThan(0)))));

        BooleanIndexing.applyWhere(
                gains,
                Conditions.lessThan(minGain),
                new Value(minGain));


        INDArray gradChange = gains.mul(yGrads);

        if(useAdaGrad)
            gradChange.muli(adaGrad.getLearningRates(gradChange));
        else
            gradChange.muli(learningRate);


        yIncs.muli(momentum).subi(gradChange);


        double cost = p.mul(log(p.div(q))).sum(Integer.MAX_VALUE).getDouble(0);
        return new Pair<>(cost,yIncs);
    }


    public void step(INDArray y,INDArray p,int i) {
        Pair<Double,INDArray> costGradient = gradient(y,p);
        INDArray yIncs = costGradient.getSecond();
        log.info("Cost at iteration " + i + " was " + costGradient.getFirst());
        y.addi(yIncs);
        //np.tile(np.mean(Y, 0), (n, 1))
        y.addi(yIncs).subiRowVector(y.mean(0));
        y.subi(Nd4j.tile(y.mean(0), new int[]{y.rows(), 1}));
    }


    /**
     * Plot tsne
     * @param matrix the matrix to plot
     * @param nDims the number
     * @param initialDims
     * @param labels
     * @throws IOException
     */
    public void plot(INDArray matrix,int nDims,int initialDims,List<String> labels) throws IOException {

        INDArray y = calculate(matrix,nDims,perplexity);


        String path = writeMatrix(y);
        String labelPath = UUID.randomUUID().toString();

        File f = new File(labelPath);
        FileUtils.writeLines(f,labels);
        String command = String.format(commandTemplate,path,nDims,perplexity,initialDims,labelPath);
        Process is = Runtime.getRuntime().exec(command);

        log.info("Std out " + IOUtils.readLines(is.getInputStream()).toString());
        log.error(IOUtils.readLines(is.getErrorStream()).toString());

    }




    protected String writeMatrix(INDArray matrix) throws IOException {
        String filePath = System.getProperty("java.io.tmpdir") + File.separator +  UUID.randomUUID().toString();
        File write = new File(filePath);
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(write,true));
        write.deleteOnExit();
        for(int i = 0; i < matrix.rows(); i++) {
            INDArray row = matrix.getRow(i);
            StringBuffer sb = new StringBuffer();
            for(int j = 0; j < row.length(); j++) {
                sb.append(String.format("%.10f", row.getDouble(j)));
                if(j < row.length() - 1)
                    sb.append(",");
            }
            sb.append("\n");
            String line = sb.toString();
            bos.write(line.getBytes());
            bos.flush();
        }

        bos.close();
        return filePath;
    }

    public static class Builder {
        private int maxIter = 1000;
        private double realMin = 1e-12f;
        private double initialMomentum = 5e-1f;
        private double finalMomentum = 8e-1f;
        private double momentum = 5e-1f;
        private int switchMomentumIteration = 100;
        private boolean normalize = true;
        private boolean usePca = false;
        private int stopLyingIteration = 100;
        private double tolerance = 1e-5f;
        private double learningRate = 1e-1f;
        private boolean useAdaGrad = true;
        private double perplexity = 30;
        private double minGain = 1e-1f;


        public Builder minGain(double minGain) {
            this.minGain = minGain;
            return this;
        }

        public Builder perplexity(double perplexity) {
            this.perplexity = perplexity;
            return this;
        }

        public Builder useAdaGrad(boolean useAdaGrad) {
            this.useAdaGrad = useAdaGrad;
            return this;
        }

        public Builder learningRate(double learningRate) {
            this.learningRate = learningRate;
            return this;
        }


        public Builder tolerance(double tolerance) {
            this.tolerance = tolerance;
            return this;
        }

        public Builder stopLyingIteration(int stopLyingIteration) {
            this.stopLyingIteration = stopLyingIteration;
            return this;
        }

        public Builder usePca(boolean usePca) {
            this.usePca = usePca;
            return this;
        }

        public Builder normalize(boolean normalize) {
            this.normalize = normalize;
            return this;
        }

        public Builder setMaxIter(int maxIter) {
            this.maxIter = maxIter;
            return this;
        }

        public Builder setRealMin(double realMin) {
            this.realMin = realMin;
            return this;
        }

        public Builder setInitialMomentum(double initialMomentum) {
            this.initialMomentum = initialMomentum;
            return this;
        }

        public Builder setFinalMomentum(double finalMomentum) {
            this.finalMomentum = finalMomentum;
            return this;
        }



        public Builder setMomentum(double momentum) {
            this.momentum = momentum;
            return this;
        }

        public Builder setSwitchMomentumIteration(int switchMomentumIteration) {
            this.switchMomentumIteration = switchMomentumIteration;
            return this;
        }

        public Tsne build() {
            return new Tsne(maxIter, realMin, initialMomentum, finalMomentum, momentum, switchMomentumIteration,normalize,usePca,stopLyingIteration,tolerance,learningRate,useAdaGrad,perplexity,minGain);
        }

    }
}