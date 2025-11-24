public class Sample {
    int global;

    public int compute(int n) {
        int sum = 0;

        if (n > 0) {
            sum = n;
        } else {
            sum = -n;
        }

        for (int i = 0; i < sum; i++) {
            global += i;
        }

        System.out.println(global);
        return global;
    }
}
