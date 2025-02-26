package gov.cdc.perf.histogram;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public interface Histogram {
    static final Random RAND = new Random();

    public interface Row {
        int getLow();
        int getHigh();
        int getCount();
    }
    List<Row> getRows();
    default int getTotal() {
        return getRows().stream().collect(Collectors.summingInt(Row::getCount));
    }
    default int getRange() {
        List<Row> l = getRows();
        return l.get(l.size()-1).getHigh() - l.get(0).getLow();
    }

    default int randomValue(Random rand) {
        return randomValue(rand, getTotal());
    }
    /**
     * Given a histogram return a random variate from the distribution described it.
     *
     * @return  The selected value.
     */
    default int randomValue() {
        return randomValue(RAND);
    }

    /**
     * Given a histogram return a random variate from the distribution described it.
     * @param rand The random number generator
     * @param max
     * @return The random variate or 0 if out of range.
     */
    default int randomValue(Random rand, int max) {
        int r = rand.nextInt(max);
        for (Row row: getRows()) {
            int count = row.getCount();
            if (r < count) {
                return row.getLow() + (r * (row.getHigh() - row.getLow())) / row.getCount();
            }
            r -= count;
        }
        return -1;
    }

    /**
     * Compute the Two-sample Kolmogorov–Smirnov test statistic of this histograms compared to that histogram.
     * Assumes bucket sizes are small enough to ensure an accurate estimate
     * (@see https://en.wikipedia.org/wiki/Kolmogorov%E2%80%93Smirnov_test#Two-sample_Kolmogorov%E2%80%93Smirnov_test)
     * @param that    The second histogram
     * @param scales    If non-null, a place to put the scale values (sample sizes) for h1 and h2
     * @return The KS test statistic
     * @throws IllegalArgumentException if the bucket sizes are not the same in each histogram
     */
    default double ksStatistic(Histogram that, double[] scales) {
        // scale factor to apply to buckets
        double scale1 = 0.0;
        double scale2 = 0.0;
        List<Row> h1 = getRows();
        List<Row> h2 = that.getRows();

        if (h1.size() != h2.size()) {
            throw new IllegalArgumentException("Histograms are not the same size");
        }
        for (int i = 0; i < h1.size(); i++) {
            Row h1r = h1.get(i);
            Row h2r = h2.get(i);
            if (h1r.getLow() != h2r.getLow() || h1r.getHigh() != h2r.getHigh()
            ) {
                throw new IllegalArgumentException("Buckets differ at position " + i);
            }

            // Accumulate counts in scale1/scale2 to turn bucket counts here into
            // probabilities on second pass
            scale1 += h1r.getCount();
            scale2 += h2r.getCount();
        }

        double maxDiff = 0.0;
        double cumDist1 = 0.0;
        double cumDist2 = 0.0;

        for (int i = 0; i < h1.size(); i++) {
            Row h1r = h1.get(i);
            Row h2r = h2.get(i);

            // Compute difference in cumulative probabilities
            cumDist1 += h1r.getCount() / scale1;
            cumDist2 += h2r.getCount() / scale2;
            double diff = Math.abs(cumDist1 - cumDist2);
            if (diff > maxDiff) {
                maxDiff = diff;
            }
        }
        // Store the scale factors (to compute level of significance)
        if (scales != null) {
            if (scales.length > 0) {
                scales[0] = scale1;
            }
            if (scales.length > 1) {
                scales[1] = scale2;
            }
        }
        return maxDiff;
    }
    /**
     * Compute the critical value for the specified alpha level with two sample KS test
     * @param alpha The alpha level
     * @param m Number of samples in first set
     * @param n Number of samples in second set
     * @return The critical value of the KS statistic at the specified Alpha level
     */
    default double ksCriticalValue(double alpha, double m, double n) {
        return Math.sqrt(- Math.log(alpha) / 2.0) * Math.sqrt((m + n) / (n * m));
    }
    /**
     * Compute the critical value for the specified alpha level with two sample KS test
     * @param alpha The alpha level
     * @param sizes An array giving sizes of first and second set
     * @return The critical value of the KS statistic at the specified Alpha level
     */
    default double ksCriticalValue(double alpha, double[] sizes) {
        return ksCriticalValue(alpha, sizes[0], sizes[1]);
    }
    
    default String _toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        double total = 0;
        for (Row r: getRows()) {
            total += r.getCount();
        }
        for (Row r: getRows()) {
            pw.printf("%5d\t%5d\t%6f%n", r.getLow(), r.getHigh(), r.getCount() / total);
        }
        pw.flush();
        return sw.toString();
    }
}
