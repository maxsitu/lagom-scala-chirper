package sample.chirper.chirp.impl

import java.time.Instant

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.datastax.driver.core.Row
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraSession
import sample.chirper.chirp.api.Chirp

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}

class ChirpRepositoryImpl(
                           db: CassandraSession
                         )(implicit val ec: ExecutionContext) extends ChirpRepository {

  private val NUM_RECENT_CHIRPS = 10
  private val SELECT_HISTORICAL_CHIRPS = "SELECT * FROM chirp WHERE userId = ? AND timestamp >= ? ORDER BY timestamp ASC"
  private val SELECT_RECENT_CHIRPS = "SELECT * FROM chirp WHERE userId = ? ORDER BY timestamp DESC LIMIT ?"

  override def getHistoricalChirps(userIds: Seq[String], timestamp: Long): Source[Chirp, NotUsed] = {
    // FIXME: direct translation from Java, can be more declarative
    val sources = ArrayBuffer.empty[Source[Chirp, NotUsed]]
    for (userId <- userIds) {
      sources += getHistoricalChirps(userId, timestamp)
    }
    // Chirps from one user are ordered by timestamp, but chirps from different
    // users are not ordered. That can be improved by implementing a smarter
    // merge that takes the timestamps into account.
    Source(sources.toList).flatMapMerge(sources.size, identity)
  }

  override def getRecentChirps(userIds: Seq[String]): Future[Seq[Chirp]] = {
    Future
      .sequence(userIds.map(getRecentChirps))
      .map(_.flatten)
      .map(limitRecentChirps)
  }

  // Helpers -----------------------------------------------------------------------------------------------------------

  private def limitRecentChirps(all: Seq[Chirp]): Seq[Chirp] = {
    // FIXME: this can be streamed
    val limited = all
      .sortWith(_.timestamp.toEpochMilli < _.timestamp.toEpochMilli)
      .take(NUM_RECENT_CHIRPS)
    limited.reverse
  }

  private def getHistoricalChirps(userId: String, timestamp: Long): Source[Chirp, NotUsed] =
    db.select(
      SELECT_HISTORICAL_CHIRPS,
      userId,
      long2Long(timestamp)
    ).map(mapChirp)

  private def getRecentChirps(userId: String): Future[Seq[Chirp]] =
    db.selectAll(
      SELECT_RECENT_CHIRPS,
      userId,
      Integer.valueOf(NUM_RECENT_CHIRPS)
    ).map(mapChirps)

  private def mapChirp(row: Row) = Chirp(
    row.getString("userId"),
    row.getString("message"),
    Instant.ofEpochMilli(row.getLong("timestamp")),
    row.getString("uuid")
  )

  private def mapChirps(chirps: Seq[Row]) = chirps.map(mapChirp)

}

//private def prepareCreateTables(): Future[Done] = {
//  session.executeCreateTable(
//  """CREATE TABLE IF NOT EXISTS follower (
//    |userId text,followedBy text,
//    |PRIMARY KEY (userId, followedBy)
//    |)""".stripMargin)
//}
//
//  private def prepareWriteFollowers(): Future[Done] = {
//  session.prepare("INSERT INTO follower (userId, followedBy) VALUES (?, ?)").map { ps =>
//  writeFollowers = ps
//  Done
//}
//}
//
//  private def processFriendChanged(event: FriendAdded): Future[List[BoundStatement]] = {
//  val bindWriteFollowers = writeFollowers.bind
//  bindWriteFollowers.setString("userId", event.friendId)
//  bindWriteFollowers.setString("followedBy", event.userId)
//  Future.successful(List(bindWriteFollowers))
//}