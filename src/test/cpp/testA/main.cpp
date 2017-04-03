#include "VVexRiscv.h"
#include "VVexRiscv_VexRiscv.h"
#ifdef REF
#include "VVexRiscv_RiscvCore.h"
#endif
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <stdio.h>
#include <iostream>
#include <stdlib.h>
#include <stdint.h>
#include <cstring>
#include <string.h>
#include <iostream>
#include <fstream>
#include <vector>

#include <iomanip>


class Memory{
public:
	uint8_t* mem[1 << 12];

	Memory(){
		for(uint32_t i = 0;i < (1 << 12);i++) mem[i] = NULL;
	}
	~Memory(){
		for(uint32_t i = 0;i < (1 << 12);i++) if(mem[i]) delete mem[i];
	}

	uint8_t* get(uint32_t address){
		if(mem[address >> 20] == NULL) {
			uint8_t* ptr = new uint8_t[1024*1024];
			for(uint32_t i = 0;i < 1024*1024;i++) ptr[i] = 0xFF;
			mem[address >> 20] = ptr;
		}
		return &mem[address >> 20][address & 0xFFFFF];
	}

	uint8_t& operator [](uint32_t address) {
		return *get(address);
	}

	/*T operator [](uint32_t address) const {
		return get(address);
	}*/
};

//uint8_t memory[1024 * 1024];

uint32_t hti(char c) {
	if (c >= 'A' && c <= 'F')
		return c - 'A' + 10;
	if (c >= 'a' && c <= 'f')
		return c - 'a' + 10;
	return c - '0';
}

uint32_t hToI(char *c, uint32_t size) {
	uint32_t value = 0;
	for (uint32_t i = 0; i < size; i++) {
		value += hti(c[i]) << ((size - i - 1) * 4);
	}
	return value;
}

void loadHexImpl(string path,Memory* mem) {
	FILE *fp = fopen(&path[0], "r");
	fseek(fp, 0, SEEK_END);
	uint32_t size = ftell(fp);
	fseek(fp, 0, SEEK_SET);
	char* content = new char[size];
	fread(content, 1, size, fp);

	int offset = 0;
	char* line = content;
	while (1) {
		if (line[0] == ':') {
			uint32_t byteCount = hToI(line + 1, 2);
			uint32_t nextAddr = hToI(line + 3, 4) + offset;
			uint32_t key = hToI(line + 7, 2);
//			printf("%d %d %d\n", byteCount, nextAddr,key);
			switch (key) {
			case 0:
				for (uint32_t i = 0; i < byteCount; i++) {
					*(mem->get(nextAddr + i)) = hToI(line + 9 + i * 2, 2);
					//printf("%x %x %c%c\n",nextAddr + i,hToI(line + 9 + i*2,2),line[9 + i * 2],line[9 + i * 2+1]);
				}
				break;
			case 2:
//				cout << offset << endl;
				offset = hToI(line + 9, 4) << 4;
				break;
			case 4:
//				cout << offset << endl;
				offset = hToI(line + 9, 4) << 16;
				break;
			default:
//				cout << "??? " << key << endl;
				break;
			}
		}

		while (*line != '\n' && size != 0) {
			line++;
			size--;
		}
		if (size <= 1)
			break;
		line++;
		size--;
	}

	delete content;
}



#define TEXTIFY(A) #A

#define assertEq(x,ref) if(x != ref) {\
	printf("\n*** %s is %d but should be %d ***\n\n",TEXTIFY(x),x,ref);\
	throw std::exception();\
}

class success : public std::exception { };

uint32_t testsCounter = 0, successCounter = 0;

uint64_t currentTime = 22;
double sc_time_stamp(){
	return currentTime;
}


class SimElement{
public:
	virtual void onReset(){}
	virtual void preCycle(){}
	virtual void postCycle(){}
};




class Workspace{
public:
	static uint32_t cycles;
	vector<SimElement*> simElements;
	Memory mem;
	string name;
	uint64_t mTimeCmp = 0;
	uint64_t mTime = 0;
	VVexRiscv* top;
	int i;
	uint32_t bootPc = -1;
	uint32_t iStall = 1,dStall = 1;
	#ifdef TRACE
	VerilatedVcdC* tfp;
	#endif

	void setIStall(bool enable) { iStall = enable; }
	void setDStall(bool enable) { dStall = enable; }

	ofstream regTraces;
	ofstream memTraces;
	ofstream logTraces;


	Workspace(string name){
		testsCounter++;
		this->name = name;
		top = new VVexRiscv;
		regTraces.open (name + ".regTrace");
		memTraces.open (name + ".memTrace");
		logTraces.open (name + ".logTrace");
		fillSimELements();
	}

	virtual ~Workspace(){
		delete top;
		#ifdef TRACE
		delete tfp;
		#endif
	}

	Workspace* loadHex(string path){
		loadHexImpl(path,&mem);
		return this;
	}

    Workspace* bootAt(uint32_t pc) { bootPc = pc;}


	virtual void iBusAccess(uint32_t addr, uint32_t *data, bool *error) {
		assertEq(addr % 4, 0);
		*data =     (  (mem[addr + 0] << 0)
					 | (mem[addr + 1] << 8)
					 | (mem[addr + 2] << 16)
					 | (mem[addr + 3] << 24));
		*error = addr == 0xF00FFF60u;
	}
	virtual void dBusAccess(uint32_t addr,bool wr, uint32_t size,uint32_t mask, uint32_t *data, bool *error) {
		assertEq(addr % (1 << size), 0);
		*error = addr == 0xF00FFF60u;
		if(wr){
			memTraces <<
			#ifdef TRACE_WITH_TIME
			(currentTime
			#ifdef REF
			-2
			 #endif
			 ) <<
			 #endif
			 " : WRITE mem" << (1 << size) << "[" << addr << "] = " << *data << endl;
			for(uint32_t b = 0;b < (1 << size);b++){
				uint32_t offset = (addr+b)&0x3;
				if((mask >> offset) & 1 == 1)
					*mem.get(addr + b) = *data >> (offset*8);
			}

			switch(addr){
			case 0xF00FFF00u: {
				cout << mem[0xF00FFF00u];
				logTraces << (char)mem[0xF00FFF00u];
				break;
			}
			case 0xF00FFF20u: pass(); break;
			case 0xF00FFF48u: mTimeCmp = (mTimeCmp & 0xFFFFFFFF00000000) | *data;break;
			case 0xF00FFF4Cu: mTimeCmp = (mTimeCmp & 0x00000000FFFFFFFF) | (((uint64_t)*data) << 32);  /*cout << "mTimeCmp <= " << mTimeCmp << endl; */break;
			}
		}else{
			*data = VL_RANDOM_I(32);
			for(uint32_t b = 0;b < (1 << size);b++){
				uint32_t offset = (addr+b)&0x3;
				*data &= ~(0xFF << (offset*8));
				*data |= mem[addr + b] << (offset*8);
			}
			switch(addr){
			case 0xF00FFF10u:
				*data = i/2;
				break;
			case 0xF00FFF40u: *data = mTime;          break;
			case 0xF00FFF44u: *data = mTime >> 32;    break;
			case 0xF00FFF48u: *data = mTimeCmp;       break;
			case 0xF00FFF4Cu: *data = mTimeCmp >> 32; break;
			}
			memTraces <<
			#ifdef TRACE_WITH_TIME
			(currentTime
			#ifdef REF
			-2
			 #endif
			 ) <<
			 #endif
			  " : READ  mem" << (1 << size) << "[" << addr << "] = " << *data << endl;

		}
	}
	virtual void postReset() {}
	virtual void checks(){}
	virtual void pass(){ throw success();}
	virtual void fail(){ throw std::exception();}
    virtual void fillSimELements();
	void dump(int i){
		#ifdef TRACE
		if(i/2 >= TRACE_START) tfp->dump(i);
		#endif
	}
	Workspace* run(uint32_t timeout = 5000){
//		cout << "Start " << name << endl;

		currentTime = 4;
		// init trace dump
		Verilated::traceEverOn(true);
		#ifdef TRACE
		tfp = new VerilatedVcdC;
		top->trace(tfp, 99);
		tfp->open((string(name)+ ".vcd").c_str());
		#endif

		// Reset
		top->clk = 0;
		top->reset = 0;

		for(SimElement* simElement : simElements) simElement->onReset();

		top->eval(); currentTime = 3;
		top->reset = 1;
		top->eval();
		#ifdef CSR
		top->timerInterrupt = 0;
		top->externalInterrupt = 1;
		#endif
		dump(0);
		top->reset = 0;
		top->eval(); currentTime = 2;


		postReset();

		#ifdef  REF
		if(bootPc != -1) top->VexRiscv->core->prefetch_pc = bootPc;
		#else
		if(bootPc != -1) top->VexRiscv->prefetch_PcManagerSimplePlugin_pcReg = bootPc;
		#endif

		try {
			// run simulation for 100 clock periods
			for (i = 16; i < timeout*2; i+=2) {
				mTime = i/2;
				#ifdef CSR
				top->timerInterrupt = mTime >= mTimeCmp ? 1 : 0;
				//if(mTime == mTimeCmp) printf("SIM timer tick\n");
				#endif
				currentTime = i;




				// dump variables into VCD file and toggle clock

				dump(i);
				top->clk = 0;
				top->eval();


				dump(i + 1);



				if(top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_valid == 1 && top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_payload_address != 0){
					regTraces <<
						#ifdef TRACE_WITH_TIME
						currentTime <<
						 #endif
						 " PC " << hex << setw(8) <<  top->VexRiscv->writeBack_PC << " : reg[" << dec << setw(2) << (uint32_t)top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_payload_address << "] = " << hex << setw(8) << top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_payload_data << endl;
				}

				for(SimElement* simElement : simElements) simElement->preCycle();

				if(top->VexRiscv->decode_arbitration_isValid){
					uint32_t expectedData;
					bool dummy;
					iBusAccess(top->VexRiscv->decode_PC, &expectedData, &dummy);
					assertEq(top->VexRiscv->decode_INSTRUCTION,expectedData);
				}

				checks();
				top->clk = 1;
				top->eval();

				cycles += 1;

				for(SimElement* simElement : simElements) simElement->postCycle();



				if (Verilated::gotFinish())
					exit(0);
			}
			cout << "timeout" << endl;
			fail();
		} catch (const success e) {
			cout <<"SUCCESS " << name <<  endl;
			successCounter++;
		} catch (const std::exception& e) {
			cout << "FAIL " <<  name << endl;
		}



		dump(i);
		dump(i+1);
		#ifdef TRACE
		tfp->close();
		#endif
		return this;
	}
};



#ifdef IBUS_SIMPLE
class IBusSimple : public SimElement{
public:
	uint32_t inst_next = VL_RANDOM_I(32);
	bool error_next = false;
	bool pending = false;

	Workspace *ws;
	VVexRiscv* top;
	IBusSimple(Workspace* ws){
		this->ws = ws;
		this->top = ws->top;
	}

	virtual void onReset(){
		top->iBus_cmd_ready = 1;
		top->iBus_rsp_ready = 1;
	}

	virtual void preCycle(){
		if (top->iBus_cmd_valid && top->iBus_cmd_ready && !pending) {
			assertEq(top->iBus_cmd_payload_pc & 3,0);
			pending = true;
			ws->iBusAccess(top->iBus_cmd_payload_pc,&inst_next,&error_next);
		}
	}

	virtual void postCycle(){
		top->iBus_rsp_ready = !pending;
		if(pending && (!ws->iStall || VL_RANDOM_I(7) < 100)){
			top->iBus_rsp_inst = inst_next;
			pending = false;
			top->iBus_rsp_ready = 1;
			top->iBus_rsp_error = error_next;
		}
		if(ws->iStall) top->iBus_cmd_ready = VL_RANDOM_I(7) < 100 && !pending;
	}
};
#endif


#ifdef IBUS_CACHED
class IBusCached : public SimElement{
public:
	uint32_t inst_next = VL_RANDOM_I(32);
	bool error_next = false;
	uint32_t pendingCount = 0;
	uint32_t address;

	Workspace *ws;
	VVexRiscv* top;
	IBusCached(Workspace* ws){
		this->ws = ws;
		this->top = ws->top;
	}


	virtual void onReset(){
		top->iBus_cmd_ready = 1;
		top->iBus_rsp_valid = 0;
	}

	virtual void preCycle(){
		if (top->iBus_cmd_valid && top->iBus_cmd_ready && pendingCount == 0) {
			assertEq(top->iBus_cmd_payload_address & 3,0);
			pendingCount = 8;
			address = top->iBus_cmd_payload_address;
		}
	}

	virtual void postCycle(){
		bool error;
		top->iBus_rsp_valid = 0;
		if(pendingCount != 0 && (!ws->iStall || VL_RANDOM_I(7) < 100)){
			ws->iBusAccess(address,&top->iBus_rsp_payload_data,&error);
			top->iBus_rsp_payload_error = error;
			pendingCount--;
			address = (address & ~0x1F) + ((address + 4) & 0x1F);
			top->iBus_rsp_valid = 1;
		}
		if(ws->iStall) top->iBus_cmd_ready = VL_RANDOM_I(7) < 100 && pendingCount == 0;
	}
};
#endif

#ifdef DBUS_SIMPLE
class DBusSimple : public SimElement{
public:
	uint32_t data_next = VL_RANDOM_I(32);
	bool error_next = false;
	bool pending = false;

	Workspace *ws;
	VVexRiscv* top;
	DBusSimple(Workspace* ws){
		this->ws = ws;
		this->top = ws->top;
	}

	virtual void onReset(){
		top->dBus_cmd_ready = 1;
		top->dBus_rsp_ready = 1;
	}

	virtual void preCycle(){
		if (top->dBus_cmd_valid && top->dBus_cmd_ready) {
			pending = true;
			data_next = top->dBus_cmd_payload_data;
			ws->dBusAccess(top->dBus_cmd_payload_address,top->dBus_cmd_payload_wr,top->dBus_cmd_payload_size,0xF,&data_next,&error_next);
		}
	}

	virtual void postCycle(){
		top->dBus_rsp_ready = 0;
		if(pending && (!ws->dStall || VL_RANDOM_I(7) < 100)){
			pending = false;
			top->dBus_rsp_ready = 1;
			top->dBus_rsp_data = data_next;
			top->dBus_rsp_error = error_next;
		} else{
			top->dBus_rsp_data = VL_RANDOM_I(32);
		}

		if(ws->dStall) top->dBus_cmd_ready = VL_RANDOM_I(7) < 100 && !pending;
	}
};
#endif

#ifdef DBUS_CACHED
class DBusCached : public SimElement{
public:
	uint32_t address;
	bool error_next = false;
	uint32_t pendingCount = 0;
	bool wr;

	Workspace *ws;
	VVexRiscv* top;
	DBusCached(Workspace* ws){
		this->ws = ws;
		this->top = ws->top;
	}

	virtual void onReset(){
		top->dBus_cmd_ready = 1;
		top->dBus_rsp_valid = 0;
	}

	virtual void preCycle(){
		if (top->dBus_cmd_valid && top->dBus_cmd_ready) {
			if(pendingCount == 0){
				pendingCount = top->dBus_cmd_payload_length;
				address = top->dBus_cmd_payload_address;
				wr = top->dBus_cmd_payload_wr;
			}
			if(top->dBus_cmd_payload_wr){
				ws->dBusAccess(address,top->dBus_cmd_payload_wr,2,top->dBus_cmd_payload_mask,&top->dBus_cmd_payload_data,&error_next);
				address += 4;
				pendingCount--;
			}
		}
	}

	virtual void postCycle(){
		if(pendingCount != 0 && !wr && (!ws->dStall || VL_RANDOM_I(7) < 100)){
			ws->dBusAccess(address,0,2,0,&top->dBus_rsp_payload_data,&error_next);
			top->dBus_rsp_valid = 1;
			address += 4;
			pendingCount--;
		} else{
			top->dBus_rsp_valid = 0;
			top->dBus_rsp_payload_data = VL_RANDOM_I(32);
		}

		top->dBus_cmd_ready = (ws->dStall ? VL_RANDOM_I(7) < 100 : 1) && (pendingCount == 0 || wr);
	}
};
#endif



void Workspace::fillSimELements(){
	#ifdef IBUS_SIMPLE
		simElements.push_back(new IBusSimple(this));
	#endif
	#ifdef IBUS_CACHED
		simElements.push_back(new IBusCached(this));
	#endif
	#ifdef DBUS_SIMPLE
		simElements.push_back(new DBusSimple(this));
	#endif
	#ifdef DBUS_CACHED
		simElements.push_back(new DBusCached(this));
	#endif
}


uint32_t Workspace::cycles = 0;

#ifndef REF
#define testA1ReagFileWriteRef {1,10},{2,20},{3,40},{4,60}
#define testA2ReagFileWriteRef {5,1},{7,3}
uint32_t regFileWriteRefArray[][2] = {
	testA1ReagFileWriteRef,
	testA1ReagFileWriteRef,
	testA2ReagFileWriteRef,
	testA2ReagFileWriteRef
};

class TestA : public Workspace{
public:


	uint32_t regFileWriteRefIndex = 0;

	TestA() : Workspace("testA") {
		loadHex("../../resources/hex/testA.hex");
	}

	virtual void checks(){
		if(top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_valid == 1 && top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_payload_address != 0){
			assertEq(top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_payload_address, regFileWriteRefArray[regFileWriteRefIndex][0]);
			assertEq(top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_payload_data, regFileWriteRefArray[regFileWriteRefIndex][1]);
			//printf("%d\n",i);

			regFileWriteRefIndex++;
			if(regFileWriteRefIndex == sizeof(regFileWriteRefArray)/sizeof(regFileWriteRefArray[0])){
				pass();
			}
		}
	}
};

class TestX28 : public Workspace{
public:
	uint32_t refIndex = 0;
	uint32_t *ref;
	uint32_t refSize;

	TestX28(string name, uint32_t *ref, uint32_t refSize) : Workspace(name) {
		this->ref = ref;
		this->refSize = refSize;
		loadHex("../../resources/hex/" + name + ".hex");
	}

	virtual void checks(){
		if(top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_valid == 1 && top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_payload_address == 28){
			assertEq(top->VexRiscv->writeBack_RegFilePlugin_regFileWrite_payload_data, ref[refIndex]);
			//printf("%d\n",i);

			refIndex++;
			if(refIndex == refSize){
				pass();
			}
		}
	}
};


class RiscvTest : public Workspace{
public:
	RiscvTest(string name) : Workspace(name) {
		loadHex("../../resources/hex/" + name + ".hex");
		bootAt(0x800000bcu);
	}

	virtual void postReset() {
//		#ifdef CSR
//		top->VexRiscv->prefetch_PcManagerSimplePlugin_pcReg = 0x80000000u;
//		#else
//		#endif
	}

	virtual void checks(){
		if(/*top->VexRiscv->writeBack_arbitration_isValid == 1 && */top->VexRiscv->writeBack_INSTRUCTION == 0x00000073){
			uint32_t code = top->VexRiscv->RegFilePlugin_regFile[28];
			if((code & 1) == 0){
				cout << "Wrong error code"<< endl;
				fail();
			}
			if(code == 1){
				pass();
			}else{
				cout << "Error code " << code/2 << endl;
				fail();
			}
		}
	}

	virtual void iBusAccess(uint32_t addr, uint32_t *data, bool *error){
		Workspace::iBusAccess(addr,data,error);
		if(*data == 0x0ff0000f) *data = 0x00000013;
	}
};
#endif
class Dhrystone : public Workspace{
public:

	Dhrystone(string name,bool iStall, bool dStall) : Workspace(name) {
		setIStall(iStall);
		setDStall(dStall);
		loadHex("../../resources/hex/" + name + ".hex");
	}

	virtual void checks(){

	}

	virtual void pass(){
		FILE *refFile = fopen((name + ".logRef").c_str(), "r");
    	fseek(refFile, 0, SEEK_END);
    	uint32_t refSize = ftell(refFile);
    	fseek(refFile, 0, SEEK_SET);
    	char* ref = new char[refSize];
    	fread(ref, 1, refSize, refFile);
    	

    	logTraces.flush();
		FILE *logFile = fopen((name + ".logTrace").c_str(), "r");
    	fseek(logFile, 0, SEEK_END);
    	uint32_t logSize = ftell(logFile);
    	fseek(logFile, 0, SEEK_SET);
    	char* log = new char[logSize];
    	fread(log, 1, logSize, logFile);
    	
    	if(refSize > logSize || memcmp(log,ref,refSize))
    		fail();
		else
			Workspace::pass();
	}
};


string riscvTestMain[] = {
	"rv32ui-p-simple",
	"rv32ui-p-lui",
	"rv32ui-p-auipc",
	"rv32ui-p-jal",
	"rv32ui-p-jalr",
	"rv32ui-p-beq",
	"rv32ui-p-bge",
	"rv32ui-p-bgeu",
	"rv32ui-p-blt",
	"rv32ui-p-bltu",
	"rv32ui-p-bne",
	"rv32ui-p-add",
	"rv32ui-p-addi",
	"rv32ui-p-and",
	"rv32ui-p-andi",
	"rv32ui-p-or",
	"rv32ui-p-ori",
	"rv32ui-p-sll",
	"rv32ui-p-slli",
	"rv32ui-p-slt",
	"rv32ui-p-slti",
	"rv32ui-p-sra",
	"rv32ui-p-srai",
	"rv32ui-p-srl",
	"rv32ui-p-srli",
	"rv32ui-p-sub",
	"rv32ui-p-xor",
	"rv32ui-p-xori"
};

string riscvTestMemory[] = {
	"rv32ui-p-lb",
	"rv32ui-p-lbu",
	"rv32ui-p-lh",
	"rv32ui-p-lhu",
	"rv32ui-p-lw",
	"rv32ui-p-sb",
	"rv32ui-p-sh",
	"rv32ui-p-sw"
};




string riscvTestMul[] = {
	"rv32um-p-mul",
	"rv32um-p-mulh",
	"rv32um-p-mulhsu",
	"rv32um-p-mulhu"
};

string riscvTestDiv[] = {
	"rv32um-p-div",
	"rv32um-p-divu",
	"rv32um-p-rem",
	"rv32um-p-remu"
};

#include <time.h>

struct timespec timer_start(){
    struct timespec start_time;
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &start_time);
    return start_time;
}

long timer_end(struct timespec start_time){
    struct timespec end_time;
    clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &end_time);
    uint64_t diffInNanos = end_time.tv_sec*1e9 + end_time.tv_nsec -  start_time.tv_sec*1e9 - start_time.tv_nsec;
    return diffInNanos;
}

#define redo(count,that) for(uint32_t xxx = 0;xxx < count;xxx++) that

int main(int argc, char **argv, char **env) {
	Verilated::randReset(2);
	Verilated::commandArgs(argc, argv);

	printf("BOOT\n");
	timespec startedAt = timer_start();

	for(int idx = 0;idx < 1;idx++){
		#ifndef  REF
		TestA().run();
		for(const string &name : riscvTestMain){
			redo(REDO,RiscvTest(name).run();)
		}
		for(const string &name : riscvTestMemory){
			redo(REDO,RiscvTest(name).run();)
		}
		for(const string &name : riscvTestMul){
			redo(REDO,RiscvTest(name).run();)
		}
		for(const string &name : riscvTestDiv){
			redo(REDO,RiscvTest(name).run();)
		}

		#ifdef CSR
		uint32_t machineCsrRef[] = {1,11,   2,0x80000003u,   3,0x80000007u,   4,0x8000000bu,   5,6,7,0x80000007u     ,
		8,6,9,6,10,4,11,4,    12,13,0,   14,2,     15,5,16,5,17,1 };
		redo(REDO,TestX28("machineCsr",machineCsrRef, sizeof(machineCsrRef)/4).run(4e3);)
		#endif
		#endif



		#ifdef DHRYSTONE
//		Dhrystone("dhrystoneO3",false,false).run(0.05e6);

		Dhrystone("dhrystoneO3",true,true).run(1.1e6);
		Dhrystone("dhrystoneO3M",true,true).run(1.5e6);
		Dhrystone("dhrystoneO3",false,false).run(1.5e6);
		Dhrystone("dhrystoneO3M",false,false).run(1.2e6);

//		Dhrystone("dhrystoneO3ML",false,false).run(8e6);
//		Dhrystone("dhrystoneO3MLL",false,false).run(80e6);
		#endif


		#ifdef FREE_RTOS
		redo(1,Workspace("freeRTOS_demo").loadHex("../../resources/hex/freeRTOS_demo.hex")->bootAt(0x80000000u)->run(100e6);)
		#endif
	}

	uint64_t duration = timer_end(startedAt);
	cout << endl << "****************************************************************" << endl;
	cout << "Had simulate " << Workspace::cycles << " clock cycles in " << duration*1e-9 << " s (" << Workspace::cycles / (duration*1e-9) << " Khz)" << endl;
	if(successCounter == testsCounter)
		cout << "SUCCESS " << successCounter << "/" << testsCounter << endl;
	else
		cout<< "FAILURE " << testsCounter - successCounter << "/"  << testsCounter << endl;
	cout << "****************************************************************" << endl << endl;


	exit(0);
}
