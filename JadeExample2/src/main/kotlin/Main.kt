package org.example

import jade.core.Agent
import jade.core.behaviours.CyclicBehaviour
import jade.lang.acl.ACLMessage
import java.io.File
import kotlin.math.abs
import jade.core.Profile
import jade.core.ProfileImpl
import jade.core.Runtime

var messagesCount = 0
var addingsCount = 0
var divisionsCount = 0
var cyclesCount = 0
const val c_m = 1.0
const val c_L = 0.01
const val c_G = 1000
const val c_S = 0.001
const val c_d = 0.005
const val c_t = 1

class ArithmeticMeanAgent(
    val agentID: Int,
    private val mainController: MainController
) : Agent() {
    private var agentNumber = 0
    private var accumulatedNumbers = mutableMapOf<String, Int>()
    private var neighbors = listOf<Int>()
    private var phase = 1
    private var nAgentsInNetwork = 0
    private var meanValue = 0.0

    fun configureTopology(neighbors_: List<Int>, number_: Int, nAgentsInNetwork_: Int) {
        neighbors = neighbors_
        agentNumber = number_
        nAgentsInNetwork = nAgentsInNetwork_
    }

    fun getMeanValue() = meanValue

    override fun setup() {
        addBehaviour(ArithmeticMeanAgentBehaviour())
    }

    private inner class ArithmeticMeanAgentBehaviour : CyclicBehaviour() {
        override fun action() {
            when (phase) {
                1 -> {
                    // Рассылаем свой номер соседям
                    for (j in neighbors) {
                        val msg = ACLMessage(ACLMessage.INFORM)
                        msg.addReceiver(mainController.agents[j - 1].aid)
                        msg.content = agentNumber.toString()
                        send(msg)
                        messagesCount++
                    }
                    cyclesCount++
                    phase = 2
                }

                2 -> {
                    // Получаем номера соседей
                    val msg = receive()
                    if (msg != null) {
                        val num = msg.content.toInt()
                        accumulatedNumbers[msg.sender.localName] = num
                        if (accumulatedNumbers.size == neighbors.size) {
                            phase = 3
                        }
                    } else {
                        block()
                    }
                    cyclesCount++
                }

                3 -> {
                    // Рассылаем информацию о соседях
                    for (j in neighbors) {
                        val msg = ACLMessage(ACLMessage.INFORM)
                        msg.addReceiver(mainController.agents[j - 1].aid)
                        msg.content = accumulatedNumbers.toString()
                        send(msg)
                        messagesCount++
                    }
                    cyclesCount++
                    phase = 4
                }

                4 -> {
                    // Получаем информацию о соседях
                    val msg = receive()
                    if (msg != null) {
                        val content = msg.content
                        val records = content.slice(1 until content.length - 1).split(", ")
                        for (record in records) {
                            val (aid, number) = record.split("=")
                            if (aid !in accumulatedNumbers.keys) {
                                accumulatedNumbers[aid] = number.toInt()
                            }
                        }
                        if (accumulatedNumbers.size == nAgentsInNetwork) {
                            phase = 5
                        }
                    } else {
                        block()
                    }
                    cyclesCount++
                }

                5 -> {
                    // Считаем среднее арифметическое
                    meanValue = accumulatedNumbers.values.sum().toDouble() / accumulatedNumbers.size
                    println("Среднее значение у агента $agentID = $meanValue")
                    addingsCount += accumulatedNumbers.values.size
                    divisionsCount++
                    cyclesCount++
                    phase = 6
                }
            }
        }
    }
}

class MainController(
    private val nAgents: Int,
    private val numbers: List<Int>,
    private val topology: List<Pair<Int, Int>>
) {
    val agents = mutableListOf<ArithmeticMeanAgent>()
    val rt = Runtime.instance()
    val p = ProfileImpl()
    lateinit var cc: jade.wrapper.AgentContainer

    fun initAgents() {
        p.setParameter(Profile.MAIN_HOST, "localhost")
        p.setParameter(Profile.MAIN_PORT, "10098")
        p.setParameter(Profile.GUI, "true")
        cc = rt.createMainContainer(p)

        try {
            for (i in 1..nAgents) {
                val agent = ArithmeticMeanAgent(i, this)
                agents.add(agent)
            }
            for (i in 1..nAgents) {
                val agent = agents[i - 1]
                val agentNeighbors =
                    topology.filter { it.first == i }.map { it.second } + topology.filter { it.second == i }
                        .map { it.first }
                agent.configureTopology(agentNeighbors, numbers[i - 1], nAgents)
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
}

fun main() {
    val nAgents = 5
    val numbersFile = File("C:/Users/kopan/Programming/MultiagentTechnologies/JadeExample2/numbers.txt")
    val topologyFile = File("C:/Users/kopan/Programming/MultiagentTechnologies/JadeExample2/network_topology.txt")
    val numbers = numbersFile.readLines().map { it.toInt() }
    val topology = topologyFile.readLines()
        .map { Pair(it.split(" ")[0].toInt(), it.split(" ")[1].toInt()) }
    val truthMeanValue = numbers.sum().toDouble() / numbers.size

    val mc = MainController(nAgents, numbers, topology)
    mc.initAgents()

    var status = false
    while (!status) {
        status = mc.agents.all { !it.isAlive }
    }

    val approximativeMeanValue = mc.getApproximativeMeanValue()

    println("Истинное среднее арифметическое = $truthMeanValue")
    println("Вычисленное среднее значение = $approximativeMeanValue")
    println("Невязка = ${abs(truthMeanValue - approximativeMeanValue)}")
    println("Число сообщений между агентами = $messagesCount")
    println("Число сообщений в центр = 0")
    println("Число сложений = $addingsCount")
    println("Число делений = $divisionsCount")

    val cost =
        (nAgents + 1) * c_m + messagesCount * c_L + 0 * c_G + addingsCount * c_S + divisionsCount * c_d + cyclesCount * c_t
    println("Общая оценка = $cost")
}