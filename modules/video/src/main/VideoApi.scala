package lila.video

import org.joda.time.DateTime
import reactivemongo.bson._
import reactivemongo.core.commands._
import scala.concurrent.duration._

import lila.common.paginator._
import lila.db.paginator.BSONAdapter
import lila.db.Types.Coll
import lila.memo.AsyncCache
import lila.user.{ User, UserRepo }

private[video] final class VideoApi(
    videoColl: Coll,
    viewColl: Coll,
    filterColl: Coll) {

  import lila.db.BSON.BSONJodaDateTimeHandler
  import reactivemongo.bson.Macros
  private implicit val YoutubeBSONHandler = {
    import Youtube.Metadata
    Macros.handler[Metadata]
  }
  private implicit val VideoBSONHandler = Macros.handler[Video]
  private implicit val TagNbBSONHandler = Macros.handler[TagNb]
  import View.viewBSONHandler

  object video {

    private val maxPerPage = 15

    def find(id: Video.ID): Fu[Option[Video]] =
      videoColl.find(BSONDocument("_id" -> id)).one[Video]

    def save(video: Video): Funit =
      videoColl.update(
        BSONDocument("_id" -> video.id),
        BSONDocument("$set" -> video),
        upsert = true).void

    def removeNotIn(ids: List[Video.ID]) =
      videoColl.remove(
        BSONDocument("_id" -> BSONDocument("$nin" -> ids))
      ).void

    def setMetadata(id: Video.ID, metadata: Youtube.Metadata) =
      videoColl.update(
        BSONDocument("_id" -> id),
        BSONDocument("$set" -> BSONDocument("metadata" -> metadata)),
        upsert = false
      ).void

    def allIds: Fu[List[Video.ID]] =
      videoColl.find(
        BSONDocument(),
        BSONDocument("_id" -> true)
      ).cursor[BSONDocument].collect[List]() map { doc =>
          doc flatMap (_.getAs[String]("_id"))
        }

    def popular(page: Int): Fu[Paginator[Video]] = Paginator(
      adapter = new BSONAdapter[Video](
        collection = videoColl,
        selector = BSONDocument(),
        sort = BSONDocument("metadata.likes" -> -1)
      ),
      currentPage = page,
      maxPerPage = maxPerPage)

    def byTags(tags: List[Tag], page: Int): Fu[Paginator[Video]] =
      if (tags.isEmpty) popular(page)
      else Paginator(
        adapter = new BSONAdapter[Video](
          collection = videoColl,
          selector = BSONDocument(
            "tags" -> BSONDocument("$all" -> tags)
          ),
          sort = BSONDocument("metadata.likes" -> -1)
        ),
        currentPage = page,
        maxPerPage = maxPerPage)

    def byAuthor(author: String, page: Int): Fu[Paginator[Video]] =
      Paginator(
        adapter = new BSONAdapter[Video](
          collection = videoColl,
          selector = BSONDocument(
            "author" -> author
          ),
          sort = BSONDocument("metadata.likes" -> -1)
        ),
        currentPage = page,
        maxPerPage = maxPerPage)

    def similar(video: Video, max: Int): Fu[List[Video]] =
      videoColl.find(BSONDocument(
        "tags" -> BSONDocument("$in" -> video.tags),
        "_id" -> BSONDocument("$ne" -> video.id)
      )).sort(BSONDocument("metadata.likes" -> -1))
        .cursor[Video]
        .collect[List]().map { videos =>
          videos.sortBy { v => -v.similarity(video) } take max
        }

    object count {

      private val cache = AsyncCache.single(
        f = videoColl.db command Count(videoColl.name, none),
        timeToLive = 1.day)

      def clearCache = cache.clear

      def apply: Fu[Int] = cache apply true
    }
  }

  object view {

    def find(videoId: Video.ID, userId: String): Fu[Option[View]] =
      viewColl.find(BSONDocument(
        View.BSONFields.id -> View.makeId(videoId, userId)
      )).one[View]

    def add(a: View) = (viewColl insert a).void recover {
      case e: reactivemongo.core.commands.LastError if e.getMessage.contains("duplicate key error") => ()
    }

    def hasSeen(user: User, video: Video): Fu[Boolean] =
      viewColl.db command Count(viewColl.name, BSONDocument(
        View.BSONFields.id -> View.makeId(video.id, user.id)
      ).some) map (0!=)
  }

  object tag {

    def paths(filterTags: List[Tag]): Fu[List[TagNb]] = pathsCache(filterTags.sorted)

    def allPopular: Fu[List[TagNb]] = popularCache(true)

    def clearCache = pathsCache.clear >> popularCache.clear

    private val max = 25

    private val pathsCache = AsyncCache[List[Tag], List[TagNb]](
      f = filterTags => {
        val allPaths =
          if (filterTags.isEmpty) popularCache(true)
          else {
            val command = Aggregate(videoColl.name, Seq(
              Match(BSONDocument("tags" -> BSONDocument("$all" -> filterTags))),
              Project("tags" -> BSONBoolean(true)),
              Unwind("tags"),
              GroupField("tags")("nb" -> SumValue(1))
            ))
            videoColl.db.command(command) map {
              _.toList.flatMap(_.asOpt[TagNb])
            }
          }
        popularCache(true) zip allPaths map {
          case (all, paths) =>
            val tags = all map { t =>
              paths find (_._id == t._id) getOrElse TagNb(t._id, 0)
            } filterNot (_.empty) take max
            val missing = filterTags filterNot { t =>
              tags exists (_.tag == t)
            }
            val list = tags.take(max - missing.size) ::: missing.flatMap { t =>
              all find (_.tag == t)
            }
            list.sortBy { t =>
              if (filterTags contains t.tag) Int.MinValue
              else -t.nb
            }
        }
      },
      timeToLive = 1.day)

    private val popularCache = AsyncCache.single[List[TagNb]](
      f = {
        val command = Aggregate(videoColl.name, Seq(
          Project("tags" -> BSONBoolean(true)),
          Unwind("tags"),
          GroupField("tags")("nb" -> SumValue(1)),
          Sort(Seq(Descending("nb")))
        ))
        videoColl.db.command(command) map {
          _.toList.flatMap(_.asOpt[TagNb])
        }
      },
      timeToLive = 1.day)
  }
}
