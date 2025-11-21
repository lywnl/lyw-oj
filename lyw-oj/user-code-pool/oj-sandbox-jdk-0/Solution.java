class Solution {
    
    public int twoSum(int val1, int val2) {
        return val1 + val2;
    }        
        

public static void main(String[] args) {
        Solution solution = new Solution();
        // 测试用例1
        int val1 = 1;
        int val2 = 2;
        int result = solution.twoSum(val1, val2);
        System.out.println(result);
    }
}