package org.tron.core.services.jsonrpc.filters;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.TransactionRetCapsule;
import org.tron.core.db.Manager;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.JsonRpcTooManyResultException;
import org.tron.core.services.jsonrpc.TronJsonRpc.LogFilterElement;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.Protocol.TransactionInfo.Log;
import org.tron.protos.Protocol.TransactionRet;

/**
 * match events from possible blocks one by one
 */
@Slf4j(topic = "API")
public class LogMatch {

  /**
   * query criteria
   */
  private final LogFilterWrapper logFilterWrapper;
  /**
   * possible block number list
   */
  private final List<Long> blockNumList;
  private final Manager manager;

  public LogMatch(LogFilterWrapper logFilterWrapper, List<Long> blockNumList, Manager manager) {
    this.logFilterWrapper = logFilterWrapper;
    this.blockNumList = blockNumList;
    this.manager = manager;
  }

  public static List<LogFilterElement> matchBlock(LogFilter logFilter, long blockNum,
      String blockHash, List<TransactionInfo> transactionInfoList, boolean removed) {

    int txCount = transactionInfoList.size();
    List<LogFilterElement> matchedLog = new ArrayList<>();
    int logIndexInBlock = 0;

    for (int i = 0; i < txCount; i++) {
      TransactionInfo transactionInfo = transactionInfoList.get(i);
      int logCount = transactionInfo.getLogCount();

      for (int j = 0; j < logCount; j++) {
        Log log = transactionInfo.getLog(j);

        if (logFilter.matchesExactly(log)) {
          List<DataWord> topicList = new ArrayList<>();
          for (ByteString topic : log.getTopicsList()) {
            topicList.add(new DataWord(topic.toByteArray()));
          }

          LogFilterElement logFilterElement = new LogFilterElement(blockHash,
              blockNum,
              ByteArray.toHexString(transactionInfo.getId().toByteArray()),
              i,
              ByteArray.toHexString(log.getAddress().toByteArray()),
              topicList,
              ByteArray.toHexString(log.getData().toByteArray()),
              logIndexInBlock,
              removed
          );
          matchedLog.add(logFilterElement);
        }

        logIndexInBlock += 1;
      }
    }

    return matchedLog;
  }

  public LogFilterElement[] matchBlockOneByOne()
      throws BadItemException, ItemNotFoundException, JsonRpcTooManyResultException {
    long functionStartTime = System.nanoTime(); // 记录函数开始时间
    List<LogFilterElement> logFilterElementList = new ArrayList<>();

    for (long blockNum : blockNumList) {
      long loopStartTime = System.nanoTime(); // 记录循环开始时间

      TransactionRetCapsule transactionRetCapsule =
          manager.getTransactionRetStore()
              .getTransactionInfoByBlockNum(ByteArray.fromLong(blockNum));
      if (transactionRetCapsule == null) {
        //if query condition (address and topics) is empty, we will traversal every block,
        //include empty block
        continue;
      }
      TransactionRet transactionRet = transactionRetCapsule.getInstance();
      List<TransactionInfo> transactionInfoList = transactionRet.getTransactioninfoList();

      String blockHash = manager.getChainBaseManager().getBlockIdByNum(blockNum).toString();
      long matchBlockStartTime = System.nanoTime(); // 记录 matchBlock 开始时间
      List<LogFilterElement> matchedLog = matchBlock(logFilterWrapper.getLogFilter(), blockNum,
          blockHash, transactionInfoList, false);
      long matchBlockEndTime = System.nanoTime(); // 记录 matchBlock 结束时间
      logger.info("Execution time for matchBlock (blockNum: {}): {} ms", blockNum,
          (matchBlockEndTime - matchBlockStartTime) / 1_000_000);
      if (!matchedLog.isEmpty()) {
        logFilterElementList.addAll(matchedLog);
      }

      if (logFilterElementList.size() > LogBlockQuery.MAX_RESULT) {long loopEndTime = System.nanoTime(); // 记录循环结束时间
        logger.info("Execution time for blockNum {}: {} ms", blockNum,
            (loopEndTime - loopStartTime) / 1_000_000);

        throw new JsonRpcTooManyResultException(
            "query returned more than " + LogBlockQuery.MAX_RESULT + " results");
      }

      long loopEndTime = System.nanoTime(); // 记录循环结束时间
      logger.info("Execution time for blockNum {}: {} ms", blockNum,
          (loopEndTime - loopStartTime) / 1_000_000);
    }

    long functionEndTime = System.nanoTime(); // 记录函数结束时间
    logger.info("Total execution time for matchBlockOneByOne: {} ms", (functionEndTime - functionStartTime) / 1_000_000);

    return logFilterElementList.toArray(new LogFilterElement[0]);
  }

}
