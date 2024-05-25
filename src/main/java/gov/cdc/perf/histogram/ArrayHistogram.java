package gov.cdc.perf.histogram;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class ArrayHistogram implements Histogram {
    private int[][] data;
    /* Constants for Histogram bucket positions */
    private static final int BUCKET_LOW = 0;
    private static final int BUCKET_HIGH = 1;
    private static final int BUCKET_COUNT = 2;

    public class ArrayRow implements Row {
        private final int index;
        ArrayRow(int index) {
            this.index = index;
        }
        @Override
        public int getLow() {
            return data[index][BUCKET_LOW];
        }

        @Override
        public int getHigh() {
            return data[index][BUCKET_HIGH];
        }

        @Override
        public int getCount() {
            return data[index][BUCKET_COUNT];
        }
    }
    private class RowList extends AbstractList<Row> {
        @Override
        public Row get(int index) {
            return new ArrayRow(index);
        }

        @Override
        public int size() {
            return data.length;
        }
    }

    public ArrayHistogram(int[][] data) {
        // Deep copy the array.
        this.data = Arrays.copyOf(data, data.length);
        for (int i = 0; i < data.length; i++) {
            this.data[i] = Arrays.copyOf(this.data[i], this.data[i].length);
        }
    }

    public ArrayHistogram(Iterable<Integer> it, int start, int unit, boolean isCdf) {
        this(it.iterator(), start, unit, isCdf);
    }

    public ArrayHistogram(Iterator<Integer> it, int start, int unit, boolean isCdf) {
        List<int[]> l = new ArrayList<>();
        int lastCount = 0;
        while (it.hasNext()) {
            int count = it.next();
            int[] values = new int[3];
            values[BUCKET_LOW] = start;
            start += unit;
            values[BUCKET_HIGH] = start;
            values[BUCKET_COUNT] = count - lastCount;
            l.add(values);
            if (isCdf) {
                lastCount = count;
            }
        }
        data = l.toArray(new int[0][0]);
    }

    /**
     * Generate a histogram using the buckets in buckets, from a set of actual values.
     * @param values    The values to generate a histogram for.
     * @param buckets   The buckets to use.
     */
    public ArrayHistogram(Iterable<Integer> values, Histogram buckets) {
        List<Row> bucketRows = buckets.getRows();
        data = new int[bucketRows.size()][];

        for (int i = 0; i < data.length; i++) {
            Row bucket = bucketRows.get(i);
            data[i] = new int[3];
            data[i][BUCKET_LOW] = bucket.getLow();
            data[i][BUCKET_HIGH] = bucket.getHigh();
        }

        for (int value: values) {
            if (value < data[0][BUCKET_LOW]) {
                // Out of range low, don't count it.
                continue;
            }
            // For those who object to linear search, these histograms are highly skewed.
            // Binary search will likely not perform much better than twice as well as simple
            // linear search.
            for (int i = 0; i < data.length; i++) {
                if (value < data[i][BUCKET_HIGH]) {
                    data[i][BUCKET_COUNT]++;
                    break;
                }
            }
            // Again, if it didn't fit, we don't count it
        }
    }

    @Override
    public List<Row> getRows() {
        return new RowList();
    }
    
    
    @Override 
    public String toString() {
        return _toString();
    }
}
