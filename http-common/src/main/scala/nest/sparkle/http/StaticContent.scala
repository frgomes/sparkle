package nest.sparkle.http

import spray.routing.{HttpService, Route}

import nest.sparkle.util.Log

sealed trait FileOrResourceLocation
case class FileLocation(location: String) extends FileOrResourceLocation
case class ResourceLocation(location: String) extends FileOrResourceLocation

/** Spray Routes for an http service that serves static content.
  *
  * static content comes from the web resource and optionally also from a configurable
  * webRoot directory. The directory can be on the file system or in a classpath resource folder.
  */
trait StaticContent extends HttpService with Log {

  /** Subclasses set this to the default web page to display (e.g. the dashboard) */
  def webRoot: Option[FileOrResourceLocation] = None

  private lazy val staticBuiltIn = { // static data from web html/javascript files pre-bundled in the 'web' resource path
    getFromResourceDirectory("web") ~
    pathPrefix("webjars") {
      getFromResourceDirectory("META-INF/resources/webjars")
    }
  }

  private lazy val webRootRoute: Route = { // static data from the web-root folder (if provided)
    webRoot.map {
      case FileLocation(path)     => getFromDirectory(path)
      case ResourceLocation(path) => getFromResourceDirectory(path)
    } getOrElse {
      reject
    }
  }

  lazy val indexHtml: Route = { // return index.html from custom folder if web-root is provided, otherwise use built in default page
    webRoot.map {
      case FileLocation(path)     => getFromFile(path + "/index.html")
      case ResourceLocation(path) => getFromResource(path + "/index.html")
    } getOrElse {
      getFromResource("web/index.html")
    }
  }

  /** server static content (html,js,css, etc.) from the built in web resource directory and from an optionally
    * configured webRoot
    */
  lazy val staticContent: Route = get { // format: OFF
    pathSingleSlash { indexHtml } ~
    staticBuiltIn ~
    webRootRoute
  } // format: ON

}