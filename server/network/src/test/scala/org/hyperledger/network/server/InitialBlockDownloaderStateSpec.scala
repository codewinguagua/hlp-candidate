/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hyperledger.network.server

import java.util.Collections

import akka.actor.{ActorSystem, Actor}
import akka.testkit.TestActorRef
import org.hyperledger.common._
import org.hyperledger.network.Implicits._
import org.hyperledger.network.server.InitialBlockDownloaderState.PendingDownload
import org.scalamock.scalatest.proxy.MockFactory
import org.scalatest._
import org.scalatest.mock.MockitoSugar

import scala.collection.immutable.Queue

class InitialBlockDownloaderStateSpec extends FunSpec with Matchers {
  case class BlockStub(hash: BID) extends Block(null, Collections.emptyList()) {
    override def getID: BID = hash
  }

  val hash1 = Hash.of(Array[Byte](1)).toBID
  val hash2 = Hash.of(Array[Byte](2)).toBID
  val hash3 = Hash.of(Array[Byte](3)).toBID
  val hash4 = Hash.of(Array[Byte](4)).toBID
  val hash5 = Hash.of(Array[Byte](5)).toBID
  val hash6 = Hash.of(Array[Byte](6)).toBID
  val missingHashes = hash1 :: hash2 :: hash3 :: hash4 :: hash5 :: hash6 :: Nil

  val block1 = BlockStub(hash1)
  val block2 = BlockStub(hash2)
  val block3 = BlockStub(hash3)
  val block4 = BlockStub(hash4)
  val block5 = BlockStub(hash5)
  val block6 = BlockStub(hash6)

  val testConfig = InitialBlockDownloaderConfig(missingHashes.take, 4, 2, 2)

  implicit val actorSystem = ActorSystem("InitialBlockDownloaderStateSpec")
  def actorStub = new Actor {
    def receive = {
      case "test" =>
    }
  }
  val connection1 = TestActorRef(actorStub)
  val connection2 = TestActorRef(actorStub)

  describe("InitialBlockDownloaderState") {
    describe("if it is empty") {
      val state = InitialBlockDownloaderState.empty
      it("should produce no 'storePending' values") {
        val (_, toStore, newState) = InitialBlockDownloaderState.requestForStore.run(testConfig, state)
        toStore shouldBe empty
        newState shouldBe state
      }
      it("should ignore new blocks since there is no pending download for them") {
        val (_, newDownloads, newState) = InitialBlockDownloaderState.newBlock(block1).run(testConfig, state)
        newDownloads shouldBe empty
        newState shouldBe state
      }
      it("should add pending download to a new connection") {
        val (_, reassigned, newState) = InitialBlockDownloaderState.connectionsChanged(Set(connection1)).run(testConfig, state)
        reassigned shouldBe empty
        newState.connections shouldBe Set(connection1)

        val (_, newDownloads, newState2) = InitialBlockDownloaderState.fillPendingDownloads.run(testConfig, newState)
        newDownloads should have size 1
        newDownloads.head.blocks shouldBe empty
        newDownloads.head.connection shouldBe connection1
        newDownloads.head.hashes shouldBe List(hash1, hash2)
        newState2.pendingDownloads shouldBe newDownloads
        newState2.connections shouldBe Set(connection1)
        newState2.availablePeers shouldBe empty
        newState2.busyPeers shouldBe Set(connection1)
        newState2.fullSize shouldBe 2
      }
    }
    describe("if it has one active connection with a pending download") {
      val state = InitialBlockDownloaderState(Set(connection1), List(PendingDownload(List(hash1, hash2), connection1, Nil)), Queue.empty, Queue.empty)
      it("should produce no 'storePending' values") {
        val (_, toStore, newState) = InitialBlockDownloaderState.requestForStore.run(testConfig, state)
        toStore shouldBe empty
        newState shouldBe state
      }
      it("should ignore new blocks which has no pending download") {
        val (_, newDownloads, newState) = InitialBlockDownloaderState.newBlock(block5).run(testConfig, state)
        newDownloads shouldBe empty
        newState shouldBe state
      }
      it("should add a new block to the pending download") {
        val (_, newDownloads, newState) = InitialBlockDownloaderState.newBlock(block2).run(testConfig, state)
        newDownloads shouldBe empty
        newState should not be state
        newState.pendingDownloads should have size 1
        newState.pendingDownloads.head.blocks shouldBe block2 :: Nil
      }
      it("should add pending download to a new connection") {
        val (_, reassigned, newState) = InitialBlockDownloaderState.connectionsChanged(Set(connection1, connection2)).run(testConfig, state)
        reassigned shouldBe empty
        newState.connections shouldBe Set(connection1, connection2)

        val (_, newDownloads, newState2) = InitialBlockDownloaderState.fillPendingDownloads.run(testConfig, newState)
        newDownloads should have size 1
        newDownloads.head.blocks shouldBe empty
        newDownloads.head.connection shouldBe connection2
        newDownloads.head.hashes shouldBe List(hash1, hash2)
        newState2.pendingDownloads should contain theSameElementsAs (newDownloads ++ state.pendingDownloads)
        newState2.connections shouldBe Set(connection1, connection2)
        newState2.availablePeers shouldBe empty
        newState2.busyPeers shouldBe Set(connection1, connection2)
        newState2.fullSize shouldBe 4
      }
    }
    describe("if it has a pending download with one block missing") {
      val state = InitialBlockDownloaderState(Set(connection1), List(PendingDownload(List(hash1, hash2), connection1, List(block2))), Queue.empty, Queue.empty)
      it("should produce no 'storePending' values") {
        val (_, toStore, newState) = InitialBlockDownloaderState.requestForStore.run(testConfig, state)
        toStore shouldBe empty
        newState shouldBe state
      }
      it("should ignore new blocks which has no pending download") {
        val (_, newDownloads, newState) = InitialBlockDownloaderState.newBlock(block5).run(testConfig, state)
        newDownloads shouldBe empty
        newState shouldBe state
      }
      it("should replace the pending downloads with new items and place the downloaded blocks in the 'blockStoreQueue' in the correct order") {
        val (_, newDownloads, newState) = InitialBlockDownloaderState.newBlock(block1).run(testConfig, state)
        newDownloads should have size 1
        newState should not be state
        newState.pendingDownloads shouldBe newDownloads
        newState.pendingDownloads shouldBe List(PendingDownload(List(hash3, hash4), connection1, Nil))
        newState.blockStoreQueue shouldBe Queue(block1, block2)
      }
      it("should reassign the pending download the a new connection if the original disappears") {
        val (_, reassigned, newState) = InitialBlockDownloaderState.connectionsChanged(Set(connection2)).run(testConfig, state)
        reassigned shouldBe List(PendingDownload(List(hash1, hash2), connection2, List(block2)))
        newState.connections shouldBe Set(connection2)
        newState.pendingDownloads shouldBe reassigned
      }
    }
    describe("if it has blocks in the 'blockStoreQueue'") {
      val state = InitialBlockDownloaderState(Set(connection1), Nil, Queue(block1, block2), Queue.empty)
      it("should move one block to the 'storePending' queue if requested") {
        val (_, toStore, newState) = InitialBlockDownloaderState.requestForStore.run(testConfig.copy(blockStoreQueueSize = 1), state)
        toStore shouldBe Queue(block1)
        newState.storePending shouldBe Queue(block1)
        newState.blockStoreQueue shouldBe Queue(block2)
      }
      it("should move two blocks to the 'storePending' queue if requested") {
        val (_, toStore, newState) = InitialBlockDownloaderState.requestForStore.run(testConfig, state)
        toStore shouldBe Queue(block1, block2)
        newState.storePending shouldBe Queue(block1, block2)
        newState.blockStoreQueue shouldBe empty
      }
      it("should move two blocks to the 'storePending' queue even if requested more") {
        val (_, toStore, newState) = InitialBlockDownloaderState.requestForStore.run(testConfig.copy(blockStoreQueueSize = 3), state)
        toStore shouldBe Queue(block1, block2)
        newState.storePending shouldBe Queue(block1, block2)
        newState.blockStoreQueue shouldBe empty
      }
    }
    describe("if its blockStoreQueue is full") {
      val state = InitialBlockDownloaderState(Set(connection1), Nil, Queue(block1, block2, block3, block4), Queue.empty)
      it("should move two blocks to the 'storePending' queue if requested") {
        val (_, toStore, newState) = InitialBlockDownloaderState.requestForStore.run(testConfig, state)
        toStore shouldBe Queue(block1, block2)
        newState.storePending shouldBe Queue(block1, block2)
        newState.blockStoreQueue shouldBe Queue(block3, block4)
      }
    }
    describe("if its blockStoreQueue and storePending is full but the pendingDownloads is empty") {
      val state = InitialBlockDownloaderState(Set(connection1), Nil, Queue(block2, block3, block4, block5), Queue(block1))
      it("should request new downloads if the blocks are stored") {
        val testConfig_ = testConfig.copy(missingBlocks = missingHashes.tail.take)
        val (_, toDownload, newState) = InitialBlockDownloaderState.blocksStored.run(testConfig_, state)
        toDownload shouldBe List(PendingDownload(List(hash6), connection1, Nil))
        newState.storePending shouldBe empty
      }
    }
  }
}
