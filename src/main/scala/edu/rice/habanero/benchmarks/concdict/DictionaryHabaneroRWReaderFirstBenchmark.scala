package edu.rice.habanero.benchmarks.concdict

import edu.rice.habanero.benchmarks.BenchmarkRunner
import edu.rice.hj.Module0._
import edu.rice.hj.api.HjSuspendable
import edu.rice.hj.experimental.actors.ReaderWriterPolicy

/**
 *
 * @author <a href="http://shams.web.rice.edu/">Shams Imam</a> (shams@rice.edu)
 */
object DictionaryHabaneroRWReaderFirstBenchmark {

  def main(args: Array[String]) {
    BenchmarkRunner.runBenchmark(args, new DictionaryHabaneroRWReaderFirstBenchmark)
  }

  private final class DictionaryHabaneroRWReaderFirstBenchmark extends DictionaryHabaneroRWAbstractBenchmark.DictionaryHabaneroRWAbstractBenchmark {

    def runIteration() {
      finish(new HjSuspendable {
        override def run() = {
          val numWorkers: Int = DictionaryConfig.NUM_ENTITIES
          val numMessagesPerWorker: Int = DictionaryConfig.NUM_MSGS_PER_WORKER

          val master = new DictionaryHabaneroRWAbstractBenchmark.Master(numWorkers, numMessagesPerWorker, ReaderWriterPolicy.READER_PRIORITY)
          master.start()
        }
      })
    }
  }

}
