package org.example

import jade.core.Agent
import jade.core.behaviours.CyclicBehaviour
import jade.lang.acl.ACLMessage
import java.io.File
import kotlin.math.abs
import jade.core.Profile
import jade.core.ProfileImpl
import jade.core.Runtime
import jade.core.behaviours.Behaviour
import org.apache.commons.math3.distribution.NormalDistribution
import org.apache.commons.math3.distribution.UniformRealDistribution

// типы связей
const val simpleEdge = 0
const val varEdge = 1
const val delayedEdge = 2

const val p = 0.5 // вероятность доступности вероятностной связи
val noiseGenerator = NormalDistribution(0.0, 1.0)
val randomNumberGenerator = UniformRealDistribution(0.0, 1.0)
// var a = noiseGenerator.sample()

// типы рёбер
val SIMPLE = 0
val PROBABILISTIC = 1
val DELAYED = 2

fun Boolean.toInt() = if (this) 1 else 0

class ConsensusAgent(
    val agentID: Int,
    private val delta: Double = 0.5,
    private val epsilon: Double = 0.1,
    private val mainController: MainController
) : Agent() {
    private var agentNumber = 0
    private var neighbors = listOf<Pair<Int, Int>>()
    private var phase = 1
    private var currentMeanValue = 0.0
    private var savedMessage: ACLMessage? =
        null // сохранение сообщения для отправки на следующем такте в случае, если ребро с задержкой
    private var receivedNumbers = mutableListOf<Double>() // значения, полученные от соседей на текущем такте
    private var previousReceivedNumbers = mutableListOf<Double>() // значения, полученные от соседей на предыдущем такте
    private var currentReceivedMessagesCount = 0
    private var hasDelayedEdge = false
    private var hasProbabilisticEdge = false

    fun configureTopology(neighbors_: List<Pair<Int, Int>>, number_: Int) {
        neighbors = neighbors_
        agentNumber = number_
//        accumulatedNumbers[agentID.toString()] = agentNumber
        currentMeanValue = agentNumber.toDouble()
        hasDelayedEdge = neighbors.map { it.second }.contains(2)
        hasProbabilisticEdge = neighbors.map { it.second }.contains(1)
    }

    fun getMeanValue() = currentMeanValue

    override fun setup() {
        addBehaviour(ConsensusAgentBehaviour())
    }

    private inner class ConsensusAgentBehaviour : CyclicBehaviour() {
        override fun action() {
            when (phase) {
                1 -> {
                    for (j in neighbors) {
                        when (j.second) {
                            0 -> {
                                val msg = ACLMessage(ACLMessage.INFORM)
                                msg.addReceiver(mainController.agents[j.first - 1].aid)
                                msg.content = (agentNumber + noiseGenerator.sample()).toString()
                                send(msg)
                                println("$agentID -> ${j.first}: ${msg.content}")
                            }

                            1 -> {
                                val probability = randomNumberGenerator.sample()
                                if (probability > p) {
                                    val msg = ACLMessage(ACLMessage.INFORM)
                                    msg.addReceiver(mainController.agents[j.first - 1].aid)
                                    msg.content = (agentNumber + noiseGenerator.sample()).toString()
                                    send(msg)
                                    println("$agentID -> ${j.first}: ${msg.content}")
                                }
                            }

                            2 -> {
                                if (savedMessage != null) {
                                    send(savedMessage)
                                    println("$agentID -> ${j.first}: ${savedMessage?.content}")
                                }
                                savedMessage = ACLMessage(ACLMessage.INFORM)
                                savedMessage?.addReceiver(mainController.agents[j.first - 1].aid)
                                savedMessage?.content = (agentNumber + noiseGenerator.sample()).toString()
                            }
                        }
                    }
                    phase = 2
                }

                2 -> {
                    // получаем числа от соседей
                    /* TODO: возможно, имеет смысл хранить значения принятых чисел на прошлом шаге, и,
                       если ребро вероятностное, то использовать в формуле значение с предыдущего шага в
                       случае, если не удалось установить соединение, а если ребро с задержкой, то
                       использовать в формуле предыдущее состояние соотв. агента. Вроде бы формула
                       это подразумевает
                    */
                    val msg = receive()
                    if (msg != null) {
                        receivedNumbers.add(msg.content.toDouble())
                        currentReceivedMessagesCount++
                        if (currentReceivedMessagesCount == neighbors.size - hasDelayedEdge.toInt() - hasProbabilisticEdge.toInt()) {
                            phase = 3
                        }
                    } else {
                        block()
                    }
                }

                3 -> {
                    // пересчитываем среднее арифметическое
                    if (receivedNumbers.all { abs(it - currentMeanValue) < epsilon }) {
                        phase = 4
                    } else {
                        currentMeanValue += delta * receivedNumbers.sumOf { it - currentMeanValue }
                        receivedNumbers = mutableListOf()
                        currentReceivedMessagesCount = 0
                        phase = 1
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

    fun getApproximativeMeanValue() = agents[0].getMeanValue()

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
    var topology = topologyFile.readLines()
        .map {
            Triple(it.split(" ")[0].toInt(), it.split(" ")[1].toInt(), it.split(" ")[2].toInt())
        }

    val truthMeanValue = numbers.sum().toDouble() / numbers.size

    val mc = MainController(nAgents, numbers, topology)
    mc.initAgents()

    // ждём, пока все агенты завершат работу
    var isCalculated = false
    while (!isCalculated) {
        isCalculated = mc.isCalculated()
    }

    val approximativeMeanValue = mc.getApproximativeMeanValue()


    println("Истинное среднее арифметическое = $truthMeanValue")
    println("Вычисленное среднее значение = $approximativeMeanValue")
    println("Невязка = ${abs(truthMeanValue - approximativeMeanValue)}")
}