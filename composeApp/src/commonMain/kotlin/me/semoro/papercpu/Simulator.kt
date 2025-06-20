package me.semoro.papercpu

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update


data class InsnDec(
    val r: Int,
    val w: Int
)

data class ProgramData(
    val packed: ShortArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ProgramData

        if (!packed.contentEquals(other.packed)) return false

        return true
    }

    override fun hashCode(): Int {
        return packed.contentHashCode()
    }
}


fun decodeInstruction(value: Int): InsnDec {
    val src = value / 100 // high two digits
    val dst = value % 100 // low two digits

    return InsnDec(src, dst)
}

/**
 * Core simulation logic for the MOV-Only Architecture Simulator.
 * Implements the memory model, execution phases, and history buffer.
 */
class Simulator {
    // Memory array with 100 cells (addresses 00-99)
    private val _memory = MutableStateFlow(IntArray(100))
    val memory: StateFlow<IntArray> = _memory.asStateFlow()

    // Current read and write pointers for visualization
    private val _readPointer = MutableStateFlow<Int?>(null)
    val readPointer: StateFlow<Int?> = _readPointer.asStateFlow()

    private val _writePointer = MutableStateFlow<Int?>(null)
    val writePointer: StateFlow<Int?> = _writePointer.asStateFlow()

    private val _pcPointer = MutableStateFlow<Int?>(null)
    val pcPointer: StateFlow<Int?> = _pcPointer.asStateFlow()

    private val _programDataReloadCounter = MutableStateFlow<Int>(0)
    val programDataReloadCounter = _programDataReloadCounter.asStateFlow()

    val output = MutableSharedFlow<Int?>(replay = 1)

    private val _historyEmpty = MutableStateFlow<Boolean>(false)
    val historyEmpty: StateFlow<Boolean> = _historyEmpty.asStateFlow()


    // History buffer for step-back debugging
    private val historyBuffer = ArrayDeque<HistoryEntry>(100) // Fixed size of 100 entries

    init {
        resetMemory()
        resetProgram()
        decodeCurrentInstructionAndUpdatePointers()
    }

    fun decodeCurrentInstructionAndUpdatePointers() {
        val memory = _memory.value
        val pcp = memory[PC]

        _pcPointer.value = pcp

        val instr = memory[pcp]

        // Phase 1: Decode instruction
        if (instr == 0) {
            // HALT instruction, do nothing
            _readPointer.value = null
            _writePointer.value = null
            return
        }

        val src = instr / 100 // high two digits
        val dst = instr % 100 // low two digits

        // Phase 2: Place read pointer
        _readPointer.value = src

        // Phase 3: Place write pointer
        _writePointer.value = dst
    }

    /**
     * Executes a single step of the simulation.
     * The execution is split into phases as specified:
     * 1. Decode instruction
     * 2. Place read pointer
     * 3. Place write pointer
     * 4. Copy value
     * 5. Move PC to +1 address
     */
    fun step() {
        val currentMemory = _memory.value.copyOf()
        val pcBefore = currentMemory[1]
        val instr = currentMemory[pcBefore]

        // Phase 1: Decode instruction
        if (instr == 0) {
            // HALT instruction, do nothing
            return
        }

        val (src, dst) = decodeInstruction(instr)

        // Save history before making changes
        pushHistory(pcBefore, src, dst, currentMemory)

        // Phase 4: Copy value
        val newMemory = currentMemory.copyOf()
        val value = currentMemory[src]
        newMemory[dst] = value

        if (dst == OUT) {
            output.tryEmit(value)
        }

        // Phase 5: Move PC to +1 address
        val newPc = (newMemory[1] + 1) % 100
        newMemory[PC] = newPc

        // Update derived registers (cells 04-07)
        recomputeDerivedRegisters(newMemory)

        // Update memory
        _memory.value = newMemory

        decodeCurrentInstructionAndUpdatePointers()
    }

    /**
     * Recomputes the derived registers (cells 04-07) based on the current memory state.
     */
    private fun recomputeDerivedRegisters(memory: IntArray) {
        val a = memory[OP_A]
        val b = memory[OP_B]
        val c = memory[OP_C]

        memory[SUM] = (a + b) % 10000 // A + B
        memory[SUB] = (a - b + 10000) % 10000 // A - B (ensure positive result)
        memory[CMP] = if (a > b) 1 else 0 // A > B (1/0)
        memory[TRN] = if (c != 0) a else b // A if C else B
    }

    /**
     * Pushes an entry to the history buffer for step-back debugging.
     */
    private fun pushHistory(pc: Int, src: Int, dst: Int, memory: IntArray) {
        // If buffer is full, remove oldest entry
        if (historyBuffer.size >= 100) {
            historyBuffer.removeFirst()
        }

        historyBuffer.addLast(
            HistoryEntry(
                pcBefore = pc,
                instr = memory[pc],
                src = src,
                dst = dst,
                valueSrc = memory[src],
                valueDst = memory[dst]
            )
        )
        _historyEmpty.value = historyBuffer.isEmpty()
    }

    /**
     * Steps back to the previous state using the history buffer.
     * @return true if step back was successful, false if history is empty
     */
    fun stepBack(): Boolean {
        if (historyBuffer.isEmpty()) {
            return false
        }

        val entry = historyBuffer.removeLast()
        val currentMemory = _memory.value.copyOf()

        // Restore PC
        currentMemory[PC] = entry.pcBefore

        // Restore memory values
        currentMemory[entry.dst] = entry.valueDst

        // Recompute derived registers
        recomputeDerivedRegisters(currentMemory)


        _memory.value = currentMemory
        // Clear pointers
        decodeCurrentInstructionAndUpdatePointers()

        output.tryEmit(null)

        _historyEmpty.value = historyBuffer.isEmpty()

        return true
    }

    fun resetMemory() {
        val newMemory = _memory.value.copyOf()

        newMemory.fill(0, 0, 50)

        // Special addresses
        newMemory[HALT] = 0 // HALT opcode
        newMemory[PC] = 50 // PC starts at address 50

        // Initialize operands
        newMemory[OP_A] = 0 // A = 42
        newMemory[OP_B] = 0 // B = 10
        newMemory[OP_C] = 0  // C = 1

        // Compute derived registers
        recomputeDerivedRegisters(newMemory)

        _memory.value = newMemory

        decodeCurrentInstructionAndUpdatePointers()

        output.tryEmit(null)
    }

    fun resetProgram() {
        val newMemory = _memory.value.copyOf()
        newMemory.fill(0, fromIndex = 50)

        assemble(newMemory) {
            inp(OP_A)
            inp(OP_B)
            setC(CMP)
            mov(SUM, TMP)
            mov(SUB, OP_A)
            mov(TMP, OP_B)
            out(TRN)
            halt()
        }

        _memory.value = newMemory
        _programDataReloadCounter.update { i -> i + 1 }
    }

    data class AsmCtx(
        val range: IntRange,
        val instr: MutableList<Int> = mutableListOf()
    ) {
        fun mov(src: Int, dst: Int) {
            instr.add((src % 100) * 100 + (dst % 100))
        }

        fun inp(dst: Int) {
            mov(INP, dst)
        }

        fun out(src: Int) {
            mov(src, OUT)
        }

        fun setA(src: Int) {
            mov(src, OP_A)
        }

        fun setB(src: Int) {
            mov(src, OP_B)
        }

        fun setC(src: Int) {
            mov(src, OP_C)
        }

        fun halt() {
            mov(HALT, HALT)
        }
    }

    inline fun assemble(newMemory: IntArray, action: AsmCtx.() -> Unit) {
        val ctx = AsmCtx(range = 50..99).apply(action)

        ctx.instr.forEachIndexed { idx, value ->
            newMemory[idx + ctx.range.start] = value
        }
    }

    fun getProgramData(): ProgramData {
        return ProgramData(
            _memory.value.sliceArray(50..99).map {
                require(it >= 0 && it < 9999)
                it.toShort()
            }.toShortArray()
        )
    }

    fun updateProgramData(programData: ProgramData) {
        val newMemory = _memory.value.copyOf()
        for (i in 50..99) {
            newMemory[i] = programData.packed[i - 50].toInt()
        }
        _memory.value = newMemory
        reset()
        decodeCurrentInstructionAndUpdatePointers()
        _programDataReloadCounter.update { i -> i + 1 }
    }

    /**
     * Resets the simulator to its initial state.
     */
    fun reset() {
        resetMemory()
        historyBuffer.clear()
        _historyEmpty.value = historyBuffer.isEmpty()
    }

    /**
     * Updates a memory cell with a new value.
     * This will clear the history buffer as it's a manual edit.
     */
    fun updateMemory(address: Int, value: Int) {
        if (address in 0 until 100) {
            val newMemory = _memory.value.copyOf()
            newMemory[address] = value

            // Recompute derived registers if necessary
            recomputeDerivedRegisters(newMemory)

            _memory.value = newMemory
        }

        if (address in 50..99) {
            historyBuffer.clear() // Clear history on program edit
            _historyEmpty.value = historyBuffer.isEmpty()
        }
    }

    /**
     * Data class representing an entry in the history buffer.
     */
    data class HistoryEntry(
        val pcBefore: Int,
        val instr: Int,
        val src: Int,
        val dst: Int,
        val valueSrc: Int,
        val valueDst: Int
    )

    companion object {
        const val PC = 1
        const val OP_A = 2
        const val OP_B = 3
        const val SUM = 4
        const val SUB = 5
        const val CMP = 6
        const val TRN = 7
        const val OP_C = 8

        const val TMP = 9
        const val TMP2 = 10
        const val OUT = 11
        const val INP = 12
        const val HALT = 0

    }
}
