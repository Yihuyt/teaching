package cn.utcy.teaching.blockcoding.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SbParserSmokeTest {

    @Test
    void parsesNestedProgram() {
        try (SbParser parser = new SbParser(new ObjectMapper())) {
            List<SbNode.Script> scripts =
                    parser.parse("when green flag clicked\nmove ((x position) + (5)) steps");
            assertEquals(1, scripts.size());
            assertEquals(2, scripts.get(0).blocks().size());
            SbNode.Block hat = (SbNode.Block) scripts.get(0).blocks().get(0);
            assertEquals("EVENT_WHENFLAGCLICKED", hat.info().id());
            assertEquals("hat", hat.info().shape());
            SbNode.Block move = (SbNode.Block) scripts.get(0).blocks().get(1);
            assertTrue(move.children().stream().anyMatch(child ->
                    child instanceof SbNode.Block nested && "OPERATORS_ADD".equals(nested.info().id())));
        }
    }

    @Test
    void parsesChinese() {
        try (SbParser parser = new SbParser(new ObjectMapper())) {
            List<SbNode.Script> scripts = parser.parse("当绿旗被点击\n移动 (10) 步");
            SbNode.Block hat = (SbNode.Block) scripts.get(0).blocks().get(0);
            assertEquals("EVENT_WHENFLAGCLICKED", hat.info().id());
        }
    }
}
