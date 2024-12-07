package org.example

import jade.core.Agent
import jade.core.MainContainer
import jade.core.behaviours.Behaviour
import jade.core.behaviours.CyclicBehaviour
import jade.core.behaviours.OneShotBehaviour
import jade.lang.acl.ACLMessage
import java.io.File
import kotlin.math.abs
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;

// глобальные переменные для подсчёта метрик эффективности системы
var messagesCount = 0
var addingsCount = 0
var divisionsCount = 0
var cyclesCount = 0
const val c_m = 1.0 // стоимость по памяти
const val c_L = 0.01 // стоимость отправки сообщения
const val c_G = 1000 // стоимость отправки сообщения в центр
const val c_S = 0.001 // стоимость сложения (вычитания)
const val c_d = 0.005 // стоимость умножения (деления)
const val c_t = 1 // стоимость такта

class ArithmeticMeanAgent(
    id: Int,
    private val mainController: MainController? = null
) : Agent() {
    val agentID = id
    private var agentNumber = 0
    private var accumulatedNumbers = mutableMapOf<String, Int>()
    private var neighbors = listOf<ArithmeticMeanAgent>()
    private var phase = 1
    private var nAgentsInNetwork = 0
    private var meanValue = 0.0

    fun configureTopology(neighbors_: List<ArithmeticMeanAgent>, number_: Int, nAgentsInNetwork_: Int) {
        neighbors = neighbors_
        agentNumber = number_
        nAgentsInNetwork = nAgentsInNetwork_
        /*
        Вариант с MainController:
        передаём в конструктор MainController cc как внешний объект
        в этой функции читаем файл с топологией сети и числами и выбираем то, что нужно данному агенту
        cc.getAgent(id.toString()).neighbors = neighbors_
        cc.getAgent(id.toString()).agentNumber = number_
        cc.getAgent(id.toString()).nAgentsInNetwork = nAgentsInNetwork_
        при отправке сообщений в методе action() также используем cc.getAgent(receiverId.toString()).send(...)
         */
    }

    fun getMeanValue() = meanValue

    override fun setup() {
        addBehaviour(ArithmeticMeanAgentBehaviour())
    }

    private inner class ArithmeticMeanAgentBehaviour : CyclicBehaviour() {
        override fun action() {
            if (phase == 1) {
                // агент начинает работу
                for (j in neighbors) {
                    val msg = ACLMessage(ACLMessage.INFORM)
                    msg.addReceiver(j.aid)
                    msg.content = agentNumber.toString()
                    send(msg)
                    messagesCount++
                }
                cyclesCount++
                for (j in neighbors) {
                    val num = j.receive().content.toInt()
                    accumulatedNumbers[j.aid.toString()] = num
                }
                cyclesCount++

                if (accumulatedNumbers.size == neighbors.size) {
                    phase = 2
                }
            } else if (phase == 2) {
                // агент рассылает соседям информацию о других соседях
                for (j in neighbors) {
                    val msg = ACLMessage(ACLMessage.INFORM)
                    msg.addReceiver(j.aid)
                    msg.setContent(accumulatedNumbers.toString())
                    send(msg)
                    messagesCount++
                }
                cyclesCount++

                for (j in neighbors) {
                    // строка формата {a=1, b=2}
                    val content = j.receive().content
                    val records = content.slice(1..<content.length - 1).split(", ")
                    for (record in records) {
                        val (aid, number) = record.split("=")
                        if (aid !in accumulatedNumbers.keys) {
                            accumulatedNumbers[aid] = number.toInt()
                        }
                    }
                }
                cyclesCount++

                if (accumulatedNumbers.size == nAgentsInNetwork) {
                    phase = 3
                }
            } else if (phase == 3) {
                // считаем среднее арифметическое
                meanValue = accumulatedNumbers.values.sum().toDouble() / accumulatedNumbers.size
                println("Mean value in agent $agentID = $meanValue")
                phase = 4
                addingsCount += accumulatedNumbers.values.size
                divisionsCount++
                cyclesCount++
            }
        }

        override fun onEnd(): Int {
            myAgent.doDelete()
            return super.onEnd()
        }
    }
}

class MainController(
    private val nAgents: Int,
    private val numbers: List<Int>,
    private val topology: List<Pair<Int, Int>>
) {
    //    private val agents = mutableListOf<AgentController>()

    fun initAgents() {
        val rt = Runtime.instance()
        val p = ProfileImpl()
        p.setParameter(Profile.MAIN_HOST, "localhost")
        p.setParameter(Profile.MAIN_PORT, "10098")
        p.setParameter(Profile.GUI, "true")
        val cc = rt.createMainContainer(p)

        try {
            for (i in 1..nAgents) {
                val agent = cc.createNewAgent(i.toString(), "org.example.ArithmeticMeanAgent", arrayOf(i))
                // configure network
            }
            for (i in 1..nAgents) {
                cc.getAgent(i.toString()).start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
//
//        (1..nAgents).forEach { agents.add(cc.createNewAgent(it.toString(), "org.example.ArithmeticMeanAgent", null)) }
//        agents.forEach { it. }
    }
}

fun main() {
    // внешний мир
    val nAgents = 5
//    val agents = (1..nAgents).map { ArithmeticMeanAgent(it) }

    // ввод данных
    val numbersFile = File(".", "numbers.txt")
    val topologyFile = File(".", "network_topology.txt")
    val numbers = numbersFile.readLines().map { it.toInt() }
    val topology = topologyFile.readLines()
        .map { Pair(it.split(" ")[0].toInt(), it.split(" ")[1].toInt()) }

    val mc = MainController(nAgents, numbers, topology)
    mc.initAgents()

    val truthMeanValue = numbers.sum().toDouble() / numbers.size
    val approximativeMeanValue = agents[0].getMeanValue()

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

