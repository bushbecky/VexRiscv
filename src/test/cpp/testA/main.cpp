#include "VVexRiscv.h"
#include "VVexRiscv_VexRiscv.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <stdio.h>
#include <iostream>
#include <stdlib.h>
#include <stdint.h>
#include <cstring>
#include <string.h>

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
		if(mem[address >> 20] == NULL) mem[address >> 20] = new uint8_t[1024*1024];
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
			//printf("%d %d %d\n", byteCount, nextAddr,key);
			switch (key) {
			case 0:
				for (uint32_t i = 0; i < byteCount; i++) {
					*(mem->get(nextAddr + i)) = hToI(line + 9 + i * 2, 2);
					//printf("%x %x %c%c\n",nextAddr + i,hToI(line + 9 + i*2,2),line[9 + i * 2],line[9 + i * 2+1]);
				}
				break;
			case 2:
				offset = hToI(line + 9, 4) << 4;
				break;
			case 4:
				offset = hToI(line + 9, 4) << 16;
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


#define testA1ReagFileWriteRef {1,10},{2,20},{3,40},{4,60}
#define testA2ReagFileWriteRef {5,1},{7,3}
uint32_t regFileWriteRefIndex = 0;
uint32_t regFileWriteRefArray[][2] = {
	testA1ReagFileWriteRef,
	testA1ReagFileWriteRef,
	testA2ReagFileWriteRef,
	testA2ReagFileWriteRef
};

#define TEXTIFY(A) #A

#define assertEq(x,ref) if(x != ref) {\
	printf("\n*** %s is %d but should be %d ***\n\n",TEXTIFY(x),x,ref);\
	throw std::exception();\
}

class success : public std::exception { };

class Workspace{
public:

	Memory mem;
	string name;
	VVexRiscv* top;
	int i;

	Workspace(string name){
		this->name = name;
		top = new VVexRiscv;
	}

	virtual ~Workspace(){
		delete top;
	}

	Workspace* loadHex(string path){
		loadHexImpl(path,&mem);
		return this;
	}

	virtual void postReset() {}
	virtual void checks(){}
	void pass(){ throw success();}
	void fail(){ throw std::exception();}

	Workspace* run(uint32_t timeout = 5000){
//		cout << "Start " << name << endl;

		// init trace dump
		Verilated::traceEverOn(true);
		VerilatedVcdC* tfp = new VerilatedVcdC;
		top->trace(tfp, 99);
		tfp->open((string(name)+ ".vcd").c_str());

		// Reset
		top->clk = 1;
		top->reset = 0;
		top->iCmd_ready = 1;
		top->dCmd_ready = 1;
		top->eval();
		top->reset = 1;
		top->eval();
		tfp->dump(0);
		top->reset = 0;
		top->eval();

		postReset();

		try {
			// run simulation for 100 clock periods
			for (i = 16; i < timeout*2; i+=2) {

				uint32_t iRsp_inst_next = top->iRsp_inst;
				uint32_t dRsp_inst_next = VL_RANDOM_I(32);

				if (top->iCmd_valid) {
					assertEq(top->iCmd_payload_pc & 3,0);
					//printf("%d\n",top->iCmd_payload_pc);

					iRsp_inst_next =  (mem[top->iCmd_payload_pc + 0] << 0)
									| (mem[top->iCmd_payload_pc + 1] << 8)
									| (mem[top->iCmd_payload_pc + 2] << 16)
									| (mem[top->iCmd_payload_pc + 3] << 24);
				}

				if (top->dCmd_valid) {
//					assertEq(top->iCmd_payload_pc & 3,0);
					//printf("%d\n",top->iCmd_payload_pc);

					uint32_t addr = top->dCmd_payload_address;
					if(top->dCmd_payload_wr){
						for(uint32_t b = 0;b < (1 << top->dCmd_payload_size);b++){
							uint32_t offset = (addr+b)&0x3;
							*mem.get(addr + b) = top->dCmd_payload_data >> (offset*8);
						}
					}else{
						for(uint32_t b = 0;b < (1 << top->dCmd_payload_size);b++){
							uint32_t offset = (addr+b)&0x3;
							dRsp_inst_next &= ~(0xFF << (offset*8));
							dRsp_inst_next |= mem[addr + b] << (offset*8);
						}
					}
				}

				checks();



				// dump variables into VCD file and toggle clock
				for (uint32_t clk = 0; clk < 2; clk++) {
					tfp->dump(i+ clk);
					top->clk = !top->clk;

					top->eval();
				}

				top->iRsp_inst = iRsp_inst_next;
				top->dRsp_data = dRsp_inst_next;

				if (Verilated::gotFinish())
					exit(0);
			}
			cout << "timeout" << endl;
			fail();
		} catch (const success e) {
			cout <<"SUCCESS " << name <<  endl;
		} catch (const std::exception& e) {
			cout << "FAIL " <<  name << endl;
		}



		tfp->dump(i);
		tfp->dump(i+1);
		tfp->close();
		return this;
	}
};

class TestA : public Workspace{
public:
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

class RiscvTest : public Workspace{
public:
	RiscvTest(string name) : Workspace(name) {
		loadHex("../../resources/hex/" + name + ".hex");
	}

	virtual void postReset() {
		top->VexRiscv->prefetch_PcManagerSimplePlugin_pc = 0x800000bcu;
	}

	virtual void checks(){
		if(top->VexRiscv->writeBack_arbitration_isValid == 1 && top->VexRiscv->writeBack_input_INSTRUCTION == 0x00000073){
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



//isaTestsMulDiv = ["rv32ui-p-mul.hex",
//                  "rv32ui-p-mulh.hex",
//                  "rv32ui-p-mulhsu.hex",
//                  "rv32ui-p-mulhu.hex",
//                  "rv32ui-p-div.hex",
//                  "rv32ui-p-divu.hex",
//                  "rv32ui-p-rem.hex",
//                  "rv32ui-p-remu.hex"]


int main(int argc, char **argv, char **env) {
	Verilated::randReset(2);
	Verilated::commandArgs(argc, argv);
	printf("BOOT\n");
	TestA().run();

	for(const string &name : riscvTestMain){
		RiscvTest(name).run();
	}
	for(const string &name : riscvTestMemory){
		RiscvTest(name).run();
	}
	printf("exit\n");
	exit(0);
}
