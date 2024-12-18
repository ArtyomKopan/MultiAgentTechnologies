package org.example

import jade.core.Agent
import jade.core.behaviours.CyclicBehaviour
import jade.lang.acl.ACLMessage
import java.io.File
import kotlin.math.abs
import jade.core.Profile
import jade.core.ProfileImpl
import jade.core.Runtime
import org.apache.commons.math3.distribution.NormalDistribution
import org.apache.commons.math3.distribution.UniformRealDistribution
import kotlin.math.pow

const val p = 0.5 // вероятность доступности вероятностной связи
val noiseGenerator = NormalDistribution(0.0, 0.5)
val randomNumberGenerator = UniformRealDistribution(0.0, 1.0)

// типы рёбер
const val SIMPLE = 0
const val PROBABILISTIC = 1
const val DELAYED = 2

class ConsensusAgent(
    val agentID: Int,
    private var delta: Double = 0.5,
    private val epsilon: Double = 0.1,
    private val mainController: MainController
) : Agent() {
    private var agentNumber = 0
    private var neighbors = listOf<Pair<Int, Int>>()
    private var phase = 1
    private var currentMeanValue = 0.0
    private var savedMessage: ACLMessage? =
        null // сохранение сообщения для отправки на следующем такте в случае, если ребро с задержкой
    private var receivedNumbers = mutableMapOf<Int, Double>() // значения, полученные от соседей на текущем такте
    private var previousReceivedNumbers =
        mutableMapOf<Int, Double>() // значения, полученные от соседей на предыдущем такте
    private var currentReceivedMessagesCount = 0
    private var hasDelayedEdge = false
    private var hasProbabilisticEdge = false
    private var delayedEdge: Int? = null
    private var probabilisticEdge: Int? = null
    private var hasConsensus = false
    private var hasNeighborsConsensus = mutableMapOf<Int, Boolean>()

    fun configureTopology(neighbors_: List<Pair<Int, Int>>, number_: Int) {
        neighbors = neighbors_
        agentNumber = number_
        currentMeanValue = agentNumber.toDouble()
        hasDelayedEdge = neighbors.map { it.second }.contains(2)
        hasProbabilisticEdge = neighbors.map { it.second }.contains(1)
        delayedEdge = neighbors.find { it.second == DELAYED }?.first
        probabilisticEdge = neighbors.find { it.second == PROBABILISTIC }?.first
        neighbors.indices.forEach { hasNeighborsConsensus[it] = false }
        delta = 1.0 / (1 + neighbors.size)
    }

    fun getMeanValue() = currentMeanValue

    override fun setup() {
        addBehaviour(ConsensusAgentBehaviour())
    }

    private inner class ConsensusAgentBehaviour : CyclicBehaviour() {

        private fun sendMessagesToAllNeighbors() {
            for (j in neighbors) {
                when (j.second) {
                    SIMPLE -> {
                        val msg = ACLMessage(ACLMessage.INFORM)
                        msg.addReceiver(mainController.agents[j.first - 1].aid)
                        msg.content = (agentNumber + noiseGenerator.sample()).toString() + ";" + hasConsensus.toString()
                        send(msg)
                        println("$agentID -> ${j.first}: ${msg.content}")
                    }

                    PROBABILISTIC -> {
                        val probability = randomNumberGenerator.sample()
                        if (probability > p) {
                            val msg = ACLMessage(ACLMessage.INFORM)
                            msg.addReceiver(mainController.agents[j.first - 1].aid)
                            msg.content =
                                (agentNumber + noiseGenerator.sample()).toString() + ";" + hasConsensus.toString()
                            send(msg)
                            println("$agentID -> ${j.first}: ${msg.content}")
                        }
                    }

                    DELAYED -> {
                        if (savedMessage != null) {
                            send(savedMessage)
                            println("$agentID -> ${j.first}: ${savedMessage?.content}")
                        }
                        savedMessage = ACLMessage(ACLMessage.INFORM)
                        savedMessage?.addReceiver(mainController.agents[j.first - 1].aid)
                        savedMessage?.content =
                            (agentNumber + noiseGenerator.sample()).toString() + ";" + hasConsensus.toString()
                    }
                }
            }
        }

        override fun action() {
            when (phase) {
                1 -> {
                    sendMessagesToAllNeighbors()
                    phase = 2
                }

                2 -> {
                    // получаем числа от соседей
                    val msg = receive()
                    if (msg != null) {
                        val sender = msg.sender.localName.toInt()
                        val number = msg.content.split(";")[0].toDouble()
                        hasNeighborsConsensus[sender] = msg.content.split(";")[1].toBoolean()
                        receivedNumbers[sender] = number
                        currentReceivedMessagesCount++
                        currentMeanValue += delta * (number - currentMeanValue)
                        println("$agentID <- $sender: ${msg.content}")
                        // проверяем, достигнут ли консенсус
                        if (currentReceivedMessagesCount == neighbors.size) {
                            hasConsensus = receivedNumbers.values.all { abs(it - currentMeanValue).pow(2.0) <= epsilon }
                            phase = if (hasConsensus) 4 else 1
                            sendMessagesToAllNeighbors()
                            receivedNumbers = mutableMapOf()
                            currentReceivedMessagesCount = 0

                            if (hasConsensus) {
                                println(message = "Агент $agentID достиг консенсуса. Mean value = $currentMeanValue")
                            }
                        }
                    } else {
                        block()
                    }
                }

                3 -> {
                    // ждём, пока все соседи не достигнут консенсуса, и рассылаем им своё число и статус
                    if (hasNeighborsConsensus.values.all { it }) {
                        phase = 4
                    } else {
//                        sendMessagesToAllNeighbors()
                        val msg = receive()
                        if (msg != null) {
                            val sender = msg.sender.localName.toInt()
                            hasNeighborsConsensus[sender] = msg.content.split(";")[1].toBoolean()
                        } else {
                            block()
                        }
                    }
                }
            }
        }
    }

    fun isCalculated() = phase == 4
}

class MainController(
    private val nAgents: Int,
    private val numbers: List<Int>,
    private val topology: List<Triple<Int, Int, Int>>
) {
    val agents = mutableListOf<ConsensusAgent>()
    val rt = Runtime.instance()
    val p = ProfileImpl()
    lateinit var cc: jade.wrapper.AgentContainer

    fun initAgents() {
        p.setParameter(Profile.MAIN_HOST, "localhost")
        p.setParameter(Profile.MAIN_PORT, "10098")
        p.setParameter(Profile.GUI, "false")
        cc = rt.createMainContainer(p)

        try {
            for (i in 1..nAgents) {
                val agent = ConsensusAgent(i, 0.5, 0.1, this)
                agents.add(agent)
            }
            for (i in 1..nAgents) {
                val agent = agents[i - 1]
                val agentNeighbors =
                    topology.filter { it.first == i }
                        .map { Pair(it.second, it.third) } + topology.filter { it.second == i }
                        .map { Pair(it.first, it.third) }
                agent.configureTopology(agentNeighbors, numbers[i - 1])
                cc.acceptNewAgent(i.toString(), agent)
            }
            for (i in 1..nAgents) {
                cc.getAgent(i.toString()).start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    //    fun getApproximativeMeanValue() = agents[0].getMeanValue()
    fun getApproximativeMeanValue() = agents.minOf { it.getMeanValue() }

    fun isCalculated() = agents.all { it.isCalculated() }
}

fun main() {
    val nAgents = 5
    /*
    Формат файла с описанием топологии сети:
    u v flag
    u и v -- узлы сети, между которыми есть связь, flag \in {0, 1, 2} обозначает тип ребра.
    flag = 0 => обычное ребро,
    flag = 1 => связь, которая доступна с вероятностью p,
    flag = 2 => связь с задержкой
     */
    val numbersFile = File("C:/Users/kopan/Programming/MultiagentTechnologies/Task2/numbers.txt")
    val topologyFile = File("C:/Users/kopan/Programming/MultiagentTechnologies/Task2/network_topology.txt")
    val numbers = numbersFile.readLines().map { it.toInt() }
    val topology = topologyFile.readLines()
        .map {
            Triple(it.split(" ")[0].toInt(), it.split(" ")[1].toInt(), it.split(" ")[2].toInt())
        }

    val truthMeanValue = numbers.sum().toDouble() / numbers.size

    val mc = MainController(nAgents, numbers, topology)
    mc.initAgents()

    while (!mc.isCalculated()) {
        Thread.sleep(1000)
    }

    val approximativeMeanValue = mc.getApproximativeMeanValue()


    println("Истинное среднее арифметическое = $truthMeanValue")
    println("Вычисленное среднее значение = $approximativeMeanValue")
    println("Невязка = ${abs(truthMeanValue - approximativeMeanValue)}")
}