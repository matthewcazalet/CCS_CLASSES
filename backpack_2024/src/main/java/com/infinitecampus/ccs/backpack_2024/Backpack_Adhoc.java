package com.infinitecampus.ccs.backpack_2024;

import com.infinitecampus.CampusObject;
import com.infinitecampus.adhoc.AdHocFilter;
import java.sql.Connection;
import java.sql.PreparedStatement;

public class Backpack_Adhoc extends CampusObject {
    public String campusfilterID;
    public String adhocID;
    public String AdhocSQL;
    public Connection Backpack_Connection = null;

    public Backpack_Adhoc() {
    }

    public Backpack_Adhoc(CampusObject co) {
        super(co);
    }

    public Backpack_Adhoc(Connection con, String appName) {
        super(con, appName);
    }

    void Initialize() {
    }

    public void upsertScheduleAdhoc() throws Exception {
        AdHocFilter adhocfilter = new AdHocFilter(this.user);
        adhocfilter.user = this.user;
        adhocfilter.con = this.con;
    }
//DO NOT USE...
    public void upsertScheduleAdhocX() throws Exception {
        AdHocFilter adhocfilter = new AdHocFilter(this.user);
        adhocfilter.user = this.user;
        adhocfilter.con = this.con;
        this.AdhocSQL = adhocfilter.loadFilterToSQL(this.campusfilterID, new String[] { "0" }, "", true, true);
        String sql = "UPDATE [campusAdhoc] SET campusadhocModifiedDate=GETDATE(),campusAdhocText='" + this.AdhocSQL
                + "',campusAdhocfilterID=" + this.campusfilterID + " WHERE campusAdhocID = " + this.adhocID;
        PreparedStatement pstmt = this.Backpack_Connection.prepareStatement(sql);

        try {
            if (pstmt.executeUpdate() < 1) {
                sql = "INSERT INTO [campusAdhoc](campusAdhocfilterID,campusadhoctext) SELECT " + this.campusfilterID
                        + ",'" + this.AdhocSQL + "'";
                pstmt = this.Backpack_Connection.prepareStatement(sql);
                pstmt.executeUpdate();
            }
        } finally {
            if (this.con != null) {
                this.con.close();
            }

        }

    }

    public void updateScheduleAdhocs() throws Exception {
    }
}
