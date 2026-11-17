package com.ruoyi.kangaroo.ext.user;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityBoundaryIndexTest
{
    @Test void pintanIsIdentifiedByContainmentNotQuanzhouDistance()
    {
        assertEquals("350128", CityBoundaryIndex.instance().locate(25.503672,119.791197,30));
        double[] olderClient = CityBoundaryIndex.gcj(25.498,119.790);
        assertEquals("350128", CityBoundaryIndex.instance().locate(olderClient[0],olderClient[1],200));
    }
    @Test void citiesAcrossTheCountryResolveInsideTheirOwnAdministrativeArea()
    {
        double[][] samples={{26.08,119.30},{24.462059,118.087828},{24.907645,118.588929},{39.917544,116.418757},
                {31.222771,121.490317},{30.657689,104.080989},{43.796428,87.620116},{29.659472,91.132911}};
        String[] prefixes={"3501","3502","3505","1101","3101","5101","6501","5401"};
        for(int i=0;i<samples.length;i++) {
            String code=CityBoundaryIndex.instance().locate(samples[i][0],samples[i][1],20);
            assertNotNull(code,"sample "+i);
            assertTrue(code.startsWith(prefixes[i]),"sample "+i+": "+code);
        }
    }
    @Test void outsideCoverageAndLargeUncertaintyAreNotAssignedToNearbyCities()
    {
        assertNull(CityBoundaryIndex.instance().locate(0,0,20));
        assertNull(CityBoundaryIndex.instance().locate(25.503672,119.791197,100000));
        assertNull(CityBoundaryIndex.instance().locate(25.5,122.0,20));
    }
    @Test void polygonHolesAndDisconnectedIslandsAreRespected()
    {
        CityBoundaryIndex.Area area=new CityBoundaryIndex.Area();
        area.box[2]=4; area.box[3]=4;
        area.polygons=new double[][][]{{{0,0,3,0,3,3,0,3,0,0},{1,1,2,1,2,2,1,2,1,1}},{{3.5,3.5,4,3.5,4,4,3.5,4,3.5,3.5}}};
        assertTrue(area.contains(.5,.5)); assertFalse(area.contains(1.5,1.5));
        assertTrue(area.contains(3.8,3.8)); assertFalse(area.contains(3.2,3.2));
        assertTrue(area.nearBoundary(0,1,20));
    }
}
