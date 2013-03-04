/** 
Open Bank Project - Transparency / Social Finance Web Application
Copyright (C) 2011, 2012, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com 
TESOBE / Music Pictures Ltd 
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by 
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

 */
package code.api

import code.actors.EnvelopeInserter
import net.liftweb.http._
import net.liftweb.http.rest._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.Printer._
import net.liftweb.json.Extraction._
import net.liftweb.json.JsonAST._
import net.liftweb.common.{Failure,Full,Empty, Box, Loggable}
import net.liftweb.mongodb._
import net.liftweb.json.JsonAST.JString
import com.mongodb.casbah.Imports._
import _root_.java.math.MathContext
import org.bson.types._
import org.joda.time.{ DateTime, DateTimeZone }
import java.util.regex.Pattern
import _root_.net.liftweb.util._
import _root_.net.liftweb.mapper._
import _root_.net.liftweb.util.Helpers._
import _root_.net.liftweb.sitemap._
import _root_.scala.xml._
import _root_.net.liftweb.http.S._
import _root_.net.liftweb.http.RequestVar
import _root_.net.liftweb.util.Helpers._
import net.liftweb.mongodb.{ Skip, Limit }
import _root_.net.liftweb.http.S._
import _root_.net.liftweb.mapper.view._
import com.mongodb._
import code.model.dataAccess.LocalStorage
import code.model.traits.ModeratedTransaction
import code.model.traits.View
import code.model.implementedTraits.View
import code.model.traits.BankAccount
import code.model.implementedTraits.Public
import code.model.traits.Bank
import code.model.traits.User
import java.util.Date
import code.api.OAuthHandshake._
import code.model.traits.ModeratedBankAccount
import code.model.dataAccess.APIMetric
import code.model.traits.AccountOwner

object OBPAPI1_1 extends RestHelper with Loggable {

  val dateFormat = ModeratedTransaction.dateFormat
  private def getUser(httpCode : Int, tokenID : Box[String]) : Box[User] = 
  if(httpCode==200)
  {
    import code.model.Token
    Token.find(By(Token.key, tokenID.get)) match {
      case Full(token) => User.findById(token.userId.get)
      case _ => Empty   
    }         
  }
  else 
    Empty 
  
  private def logAPICall = 
    APIMetric.createRecord.
      url(S.uriAndQueryString.getOrElse("")).
      date((now: TimeSpan)).
      save  
    
  serve("obp" / "v1.1" prefix {
    
    case Nil JsonGet json => {
      logAPICall
      
      def gitCommit : String = {
        val commit = tryo{
          val properties = new java.util.Properties()
          properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"))
          properties.getProperty("git.commit.id", "")
        }
        commit getOrElse ""
      }
      
      val apiDetails = {
        ("api" ->
          ("version" -> "1.1") ~
          ("git_commit" -> gitCommit) ~
          ("hosted_by" -> 
            ("organisation" -> "TESOBE") ~
            ("email" -> "contact@tesobe.com") ~
            ("phone" -> "+49 (0)30 8145 3994"))) ~
        ("links" -> 
          ("rel" -> "banks") ~
          ("href" -> "/banks") ~
          ("method" -> "GET") ~
          ("title" -> "Returns a list of banks supported on this server"))
      }
      
      JsonResponse(apiDetails)
    }
    
    case "banks" :: Nil JsonGet json => {
      logAPICall
      def bankToJson( b : Bank) = {
        ("bank" -> 
          ("id" -> b.permalink) ~
          ("short_name" -> b.shortName) ~
          ("full_name" -> b.fullName) ~
          ("logo" -> b.logoURL) 
        )
      }

      JsonResponse("banks" -> Bank.all.map(bankToJson _ ))
    }  
  })

  serve("obp" / "v1.1" prefix {
    case "banks" :: bankId :: "accounts" :: accountId :: viewId :: "account" :: Nil JsonGet json => {
      logAPICall
      val (httpCode, data, oAuthParameters) = validator("protectedResource", "GET")
      val headers = ("Content-type" -> "application/x-www-form-urlencoded") :: Nil
      val user = getUser(httpCode, oAuthParameters.get("oauth_token"))

      case class ModeratedAccountAndViews(account: ModeratedBankAccount, views: Set[View])

      val moderatedAccountAndViews = for {
        bank <- Bank(bankId) ?~ { "bank " + bankId + " not found" } ~> 404
        account <- BankAccount(bankId, accountId) ?~ { "account " + accountId + " not found for bank" } ~> 404
        view <- View.fromUrl(viewId) ?~ { "view " + viewId + " not found for account" } ~> 404
        moderatedAccount <- account.moderatedBankAccount(view, user) ?~ { "view/account not authorised" } ~> 401
        availableViews <- Full(account.permittedViews(user))
      } yield ModeratedAccountAndViews(moderatedAccount, availableViews)

      val bankName = moderatedAccountAndViews.flatMap(_.account.bankName) getOrElse ""
      
      def viewJson(view: View): JObject = {

        val isPublic: Boolean =
          view match {
            case Public => true
            case _ => false
          }

        ("id" -> view.id) ~
        ("short_name" -> view.name) ~
        ("description" -> view.description) ~
        ("is_public" -> isPublic)
      }

      def ownerJson(accountOwner: AccountOwner): JObject = {
        ("user_id" -> accountOwner.id) ~
        ("user_provider" -> bankName) ~
        ("display_name" -> accountOwner.name)
      }

      def balanceJson(account: ModeratedBankAccount): JObject = {
        ("currency" -> account.currency) ~
        ("amount" -> account.balance)
      }

      def json(account: ModeratedBankAccount, views: Set[View]): JObject = {
        ("account" ->
        ("number" -> account.number) ~
        ("owners" -> account.owners.flatten.map(ownerJson)) ~
        ("type" -> account.accountType) ~
        ("balance" -> balanceJson(account)) ~
        ("IBAN" -> account.iban) ~
        ("views_available" -> views.map(viewJson)))
      }

      moderatedAccountAndViews.map(mv => JsonResponse(json(mv.account, mv.views)))
    }

  })
}