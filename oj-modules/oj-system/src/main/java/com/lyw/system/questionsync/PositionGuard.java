package com.lyw.system.questionsync;

public interface PositionGuard {
    /** 只读校验同源、ROW/FULL、持久化位点仍可续传。 */
    BinlogPosition check() throws Exception;
    /** 对账页读取完成后的主库全局屏障；不读取业务行。 */
    BinlogPosition capture() throws Exception;
}
