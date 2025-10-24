package com.infinitecampus.ccs.backpack_2024;

import com.infinitecampus.utility.Blowfish;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Iterator;
import java.util.Objects;

public class Backpack_Schedule implements AutoCloseable {
   public String Identifier;
   public String Name;
   public Date Expire;
   public boolean Campus_Access = true;
   public boolean Guardian_Access = true;
   public boolean Student_Access = true;
   public int ModifiedID = 0;
   public int FolderID = 0;
   public int ConfirmID = 0;
   public String ConfirmText;
   public String ConfirmAcknowledgement;
   public boolean OccursDaily = false;
   public int campusAdhocFilterID;
   public String campusAdhocText;
   public Date StartDate;
   public Date EndDate;
   public Date ScheduledTime;
   public String EmailNotification;
   public Connection Backpack_Connection = null;
   public String dbName = "CCS_Report";
   public String FolderName="";
	public int DocumentCount=0;
	public java.time.LocalDateTime NextRun;
	public String LastRun="";
	public String LastRunStatus="";
   public Backpack_CustomReport CustomReport;
   PreparedStatement pstmt;
   ResultSet rs;
   String sCurrentDate = DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now());

   public Backpack_Schedule() {
   }

   public Backpack_Schedule(String _Identifier, String _dbName, Connection _Backpack_Connection) {
      this.Identifier = _Identifier;
      this.dbName = _dbName;
      this.Backpack_Connection = _Backpack_Connection;
      String sql = "";

      try {
         sql = "SELECT s.*,codeconfirmID,confirm_text,confirm_description,ca.campusAdhocText FROM [" + this.dbName + "]..Schedule s WITH (NOLOCK) LEFT OUTER JOIN [" + this.dbName + "]..CodeConfirm cc WITH (NOLOCK) ON cc.scheduleID=s.scheduleID  " + "LEFT OUTER JOIN [" + this.dbName + "].. [campusAdhoc]ca ON ca.campusadhocfilterID=s.campusadhocfilterID WHERE s.scheduleID= ?";
         this.pstmt = this.Backpack_Connection.prepareStatement(sql);
         //this.pstmt.setString(1, Blowfish.decrypt(this.Identifier, this.sCurrentDate));
         this.pstmt.setInt(1, Integer.parseInt(Blowfish.decrypt(this.Identifier, this.sCurrentDate)));
         this.rs = this.pstmt.executeQuery();

         while(this.rs.next()) {
            this.Name = this.rs.getString("schedule_name");
            this.Expire = this.rs.getDate("schedule_output_expires");
            this.Campus_Access = Objects.equals(this.rs.getString("schedule_access").substring(0, 1), new String("1"));
            this.Guardian_Access = Objects.equals(this.rs.getString("schedule_access").substring(1, 2), new String("1"));
            this.Student_Access = Objects.equals(this.rs.getString("schedule_access").substring(2, 3), new String("1"));
            this.ModifiedID = this.rs.getInt("modifiedbyID");
            this.FolderID = this.rs.getInt("folderID");
            this.OccursDaily = this.rs.getInt("schedule_freq_Daily") == 1;
            this.StartDate = this.rs.getDate("schedule_start_date");
            this.EndDate = this.rs.getDate("schedule_end_date");
            this.ScheduledTime = this.rs.getTime("schedule_time");
            this.EmailNotification = this.rs.getString("schedule_notification");
            this.ConfirmID = this.rs.getInt("codeconfirmID");
            this.ConfirmText = this.rs.getString("confirm_text");
            this.ConfirmAcknowledgement = this.rs.getString("confirm_description");
            this.campusAdhocFilterID = this.rs.getInt("campusAdhocfilterID");
            this.campusAdhocText = this.rs.getString("campusAdhocText");
            if (this.rs.getObject("schedulerptID") != null) {
               this.CustomReport = new Backpack_CustomReport();
               this.CustomReport.Identifier = Blowfish.encrypt(this.rs.getString("schedulerptID"), this.sCurrentDate);
            }
         }
      } catch (Exception var9) {
         System.out.println(var9);
      } finally {
         this.close_connections();
      }

   }
   public boolean Upsert() {
      boolean breturn = false;
      String sql = "";
  
      try {
          SimpleDateFormat outputDateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
          int _irecordcount;
          
          if (this.Identifier != null && !this.Identifier.isEmpty()) {
              // UPDATE existing record
              sql = "UPDATE [" + this.dbName + "]..Schedule SET " +
                    "schedule_Name=?, " +
                    "schedule_modifydate=GETDATE(), " +
                    "schedule_output_expires=?, " +
                    "schedule_access=?, " +
                    "modifiedbyID=?, " +
                    "folderID=?, " +
                    "schedule_freq_Daily=?, " +
                    "schedule_start_date=?, " +
                    "schedule_end_date=?, " +
                    "schedule_time=?, " +
                    "schedule_notification=?, " +
                    "campusAdhocfilterID=?, " +
                    "scheduleRptID=? " +
                    "WHERE scheduleID=?";
                    
              this.pstmt = this.Backpack_Connection.prepareStatement(sql);
              this.pstmt.setString(1, this.Name);
              
              if (this.Expire == null) {
                  this.pstmt.setNull(2, java.sql.Types.TIMESTAMP);
              } else {
                  this.pstmt.setString(2, outputDateFormat.format(this.Expire));
              }
              
              this.pstmt.setString(3, (this.Campus_Access ? "1" : "0") + 
                                     (this.Guardian_Access ? "1" : "0") + 
                                     (this.Student_Access ? "1" : "0"));
                                     
              if (this.ModifiedID == 0) {
                  this.pstmt.setNull(4, java.sql.Types.INTEGER);
              } else {
                  this.pstmt.setInt(4, this.ModifiedID);
              }
              
              if (this.FolderID == 0) {
                  this.pstmt.setNull(5, java.sql.Types.INTEGER);
              } else {
                  this.pstmt.setInt(5, this.FolderID);
              }
              
              this.pstmt.setInt(6, this.OccursDaily ? 1 : 0);
              
              if (this.StartDate == null) {
                  this.pstmt.setNull(7, java.sql.Types.TIMESTAMP);
              } else {
                  this.pstmt.setString(7, outputDateFormat.format(this.StartDate));
              }
              
              if (this.EndDate != null && this.OccursDaily) {
                  this.pstmt.setString(8, outputDateFormat.format(this.EndDate));
              } else {
                  this.pstmt.setNull(8, java.sql.Types.TIMESTAMP);
              }
              
              if (this.ScheduledTime == null) {
                  this.pstmt.setNull(9, java.sql.Types.TIMESTAMP);
              } else {
                  this.pstmt.setString(9, outputDateFormat.format(this.ScheduledTime));
              }
              
              this.pstmt.setString(10, this.EmailNotification);
              
              if (this.campusAdhocFilterID == 0) {
                  this.pstmt.setNull(11, java.sql.Types.INTEGER);
              } else {
                  this.pstmt.setInt(11, this.campusAdhocFilterID);
              }
              
              if (this.CustomReport != null && this.CustomReport.Identifier != null && !this.CustomReport.Identifier.isEmpty()) {
                  this.pstmt.setInt(12, Integer.parseInt(this.CustomReport.Identifier));
              } else {
                  this.pstmt.setNull(12, java.sql.Types.INTEGER);
              }
              
              this.pstmt.setInt(13, Integer.parseInt(Blowfish.decrypt(this.Identifier, this.sCurrentDate)));
              
              this.pstmt.executeUpdate();
              breturn = true;
              
          } else {
              // INSERT new record
              sql = "INSERT INTO [" + this.dbName + "]..Schedule(" +
                    "schedule_name, schedule_output_expires, schedule_access, folderID, modifiedByID, scheduleRptID, " +
                    "schedule_freq_Daily, schedule_start_date, schedule_end_date, schedule_time, schedule_notification, campusAdhocfilterID) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    
              this.pstmt = this.Backpack_Connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
              this.pstmt.setString(1, this.Name);
              
              if (this.Expire == null) {
                  this.pstmt.setNull(2, java.sql.Types.TIMESTAMP);
              } else {
                  this.pstmt.setString(2, outputDateFormat.format(this.Expire));
              }
              
              this.pstmt.setString(3, (this.Campus_Access ? "1" : "0") + 
                                     (this.Guardian_Access ? "1" : "0") + 
                                     (this.Student_Access ? "1" : "0"));
                                     
              if (this.FolderID == 0) {
                  this.pstmt.setNull(4, java.sql.Types.INTEGER);
              } else {
                  this.pstmt.setInt(4, this.FolderID);
              }
              
              if (this.ModifiedID == 0) {
                  this.pstmt.setNull(5, java.sql.Types.INTEGER);
              } else {
                  this.pstmt.setInt(5, this.ModifiedID);
              }
              
              if (this.CustomReport != null && this.CustomReport.Identifier != null && !this.CustomReport.Identifier.isEmpty()) {
                  this.pstmt.setInt(6, Integer.parseInt(this.CustomReport.Identifier));
              } else {
                  this.pstmt.setNull(6, java.sql.Types.INTEGER);
              }
              
              this.pstmt.setInt(7, this.OccursDaily ? 1 : 0);
              
              if (this.StartDate == null) {
                  this.pstmt.setNull(8, java.sql.Types.TIMESTAMP);
              } else {
                  this.pstmt.setString(8, outputDateFormat.format(this.StartDate));
              }
              
              if (this.EndDate != null && this.OccursDaily) {
                  this.pstmt.setString(9, outputDateFormat.format(this.EndDate));
              } else {
                  this.pstmt.setNull(9, java.sql.Types.TIMESTAMP);
              }
              
              if (this.ScheduledTime == null) {
                  this.pstmt.setNull(10, java.sql.Types.TIMESTAMP);
              } else {
                  this.pstmt.setString(10, outputDateFormat.format(this.ScheduledTime));
              }
              
              this.pstmt.setString(11, this.EmailNotification);
              
              if (this.campusAdhocFilterID == 0) {
                  this.pstmt.setNull(12, java.sql.Types.INTEGER);
              } else {
                  this.pstmt.setInt(12, this.campusAdhocFilterID);
              }
              
              _irecordcount = this.pstmt.executeUpdate();
              if (_irecordcount > 0) {
                  this.rs = this.pstmt.getGeneratedKeys();
                  this.rs.next();
                  this.Identifier = Blowfish.encrypt(String.valueOf(this.rs.getInt(1)), this.sCurrentDate);
                  breturn = true;
              }
          }
  
          // Handle Custom Report Parameters
          if (breturn && this.CustomReport != null && this.CustomReport.Identifier != null && !this.CustomReport.Identifier.isEmpty()) {
              for (Backpack_CustomReport.Parameter _param : this.CustomReport.parameters) {
                  sql = "UPDATE sp SET scheduleparamvalue=? " +
                        "FROM [" + this.dbName + "]..schedule_parameter sp " +
                        "INNER JOIN [" + this.dbName + "]..[Param] p ON sp.paramID=p.paramID " +
                        "WHERE sp.scheduleID=? AND p.paramID=?";
                        
                  this.pstmt = this.Backpack_Connection.prepareStatement(sql);
                  
                  if (_param.SelectedValue != null) {
                      this.pstmt.setString(1, _param.SelectedValue);
                  } else {
                      // Need to handle default value differently - might need a subquery
                      this.pstmt.setNull(1, java.sql.Types.VARCHAR);
                  }
                  
                  this.pstmt.setInt(2, Integer.parseInt(Blowfish.decrypt(this.Identifier, this.sCurrentDate)));
                  this.pstmt.setInt(3, Integer.parseInt(Blowfish.decrypt(_param.Identifier, this.sCurrentDate)));
                  
                  _irecordcount = this.pstmt.executeUpdate();
                  
                  if (_irecordcount <= 0) {
                      sql = "INSERT INTO [" + this.dbName + "]..Schedule_Parameter (scheduleID, paramID, scheduleparamvalue) " +
                            "SELECT ?, p.paramID, COALESCE(?, p.DefaultValue) " +
                            "FROM [" + this.dbName + "]..Param p WHERE p.paramID=?";
                            
                      this.pstmt = this.Backpack_Connection.prepareStatement(sql);
                      this.pstmt.setInt(1, Integer.parseInt(Blowfish.decrypt(this.Identifier, this.sCurrentDate)));
                      this.pstmt.setString(2, _param.SelectedValue);
                      this.pstmt.setInt(3, Integer.parseInt(Blowfish.decrypt(_param.Identifier, this.sCurrentDate)));
                      this.pstmt.executeUpdate();
                  }
              }
          }
  
          // Handle Confirm Text
          if (this.ConfirmText != null && !this.ConfirmText.isEmpty()) {
              sql = "UPDATE [" + this.dbName + "]..CodeConfirm SET confirm_text=?, confirm_description=? " +
                    "WHERE scheduleID IN (SELECT TOP 1 scheduleID FROM [" + this.dbName + "]..Schedule WHERE schedule_name=?)";
                    
              this.pstmt = this.Backpack_Connection.prepareStatement(sql);
              this.pstmt.setString(1, this.ConfirmText);
              
              if (this.ConfirmAcknowledgement != null && !this.ConfirmAcknowledgement.isEmpty()) {
                  this.pstmt.setString(2, this.ConfirmAcknowledgement);
              } else {
                  this.pstmt.setNull(2, java.sql.Types.VARCHAR);
              }
              
              this.pstmt.setString(3, this.Name);
              _irecordcount = this.pstmt.executeUpdate();
              
              if (_irecordcount == 0) {
                  sql = "INSERT INTO [" + this.dbName + "]..CodeConfirm(scheduleID, confirm_text, confirm_description) " +
                        "VALUES((SELECT TOP 1 scheduleID FROM [" + this.dbName + "]..Schedule WHERE schedule_name=?), ?, ?)";
                        
                  this.pstmt = this.Backpack_Connection.prepareStatement(sql);
                  this.pstmt.setString(1, this.Name);
                  this.pstmt.setString(2, this.ConfirmText);
                  
                  if (this.ConfirmAcknowledgement == null || this.ConfirmAcknowledgement.isEmpty()) {
                      this.pstmt.setNull(3, java.sql.Types.VARCHAR);
                  } else {
                      this.pstmt.setString(3, this.ConfirmAcknowledgement);
                  }
                  
                  this.pstmt.executeUpdate();
              }
          } else {
              sql = "DELETE [" + this.dbName + "]..CodeConfirm " +
                    "WHERE scheduleID IN (SELECT TOP 1 scheduleID FROM [" + this.dbName + "]..Schedule WHERE schedule_name=?)";
                    
              this.pstmt = this.Backpack_Connection.prepareStatement(sql);
              this.pstmt.setString(1, this.Name);
              this.pstmt.executeUpdate();
          }
          
      } catch (Exception ex) {
          System.out.println("Error in Upsert: " + ex.getMessage());
          ex.printStackTrace();
          breturn = false;
      } finally {
          try {
              this.close_connections();
          } catch (Exception ex) {
              System.out.println("Error closing connections: " + ex.getMessage());
          }
      }
  
      return breturn;
  }
   public boolean Upsert_DEP() {
      boolean breturn = false;
      String sql = "";


      try {
         SimpleDateFormat outputDateFormat = new SimpleDateFormat("MM/dd/YYYY HH:mm:ss");
         int _irecordcount;
         if (this.Identifier != null && !this.Identifier.isEmpty()) {
            sql = "UPDATE [" + this.dbName + "]..Schedule SET " + "[schedule_Name]='" + this.Name + "'" + ",[schedule_modifydate]=GETDATE()" + ",schedule_output_expires=" + (this.Expire == null ? "NULL" : "'" + outputDateFormat.format(this.Expire) + "'") + ",schedule_access='" + (this.Campus_Access ? "1" : "0") + (this.Guardian_Access ? "1" : "0") + (this.Student_Access ? "1" : "0") + "'" + ",modifiedbyID=" + (this.ModifiedID == 0 ? "NULL" : String.valueOf(this.ModifiedID)) + ",folderID=" + (this.FolderID == 0 ? "NULL" : String.valueOf(this.FolderID)) + ",schedule_freq_Daily=" + (this.OccursDaily ? "1" : "0") + ",[schedule_start_date]=" + (this.StartDate == null ? "NULL" : "'" + outputDateFormat.format(this.StartDate) + "'") + ",[schedule_end_date]=" + (this.EndDate != null && this.OccursDaily ? "'" + outputDateFormat.format(this.EndDate) + "'" : "NULL") + ",[schedule_time]=" + (this.ScheduledTime == null ? "NULL" : "'" + outputDateFormat.format(this.ScheduledTime) + "'") + ",[schedule_notification]=" + (this.EmailNotification == null ? "NULL" : "'" + this.EmailNotification + "'") + ",[campusAdhocfilterID]=" + (this.campusAdhocFilterID == 0 ? "NULL" : String.valueOf(this.campusAdhocFilterID)) + ",scheduleRptID=" + (this.CustomReport != null && this.CustomReport.Identifier != null && !this.CustomReport.Identifier.isEmpty() ? this.CustomReport.Identifier : "NULL") + " WHERE scheduleID=" + Blowfish.decrypt(this.Identifier, this.sCurrentDate);
            this.pstmt = this.Backpack_Connection.prepareStatement(sql, 1);
            this.pstmt.executeUpdate();
            breturn = true;
         } else {
            sql = "INSERT INTO [" + this.dbName + "]..Schedule(schedule_name,schedule_output_expires,schedule_access,folderID,modifiedByID,scheduleRptID," + "schedule_freq_Daily,schedule_start_date,schedule_end_date,schedule_time,schedule_notification,campusAdhocfilterID) " + "values ('" + this.Name + "'" + "," + (this.Expire == null ? "NULL" : "'" + outputDateFormat.format(this.Expire) + "'") + ",'" + (this.Campus_Access ? "1" : "0") + (this.Guardian_Access ? "1" : "0") + (this.Student_Access ? "1" : "0") + "'" + "," + (this.FolderID == 0 ? "NULL" : String.valueOf(this.FolderID)) + "," + (this.ModifiedID == 0 ? "NULL" : String.valueOf(this.ModifiedID)) + "," + (this.CustomReport != null && this.CustomReport.Identifier != null && !this.CustomReport.Identifier.isEmpty() ? this.CustomReport.Identifier : "NULL") + "," + (this.OccursDaily ? "1" : "0") + "," + (this.StartDate == null ? "NULL" : "'" + outputDateFormat.format(this.StartDate) + "'") + "," + (this.EndDate != null && this.OccursDaily ? "'" + outputDateFormat.format(this.EndDate) + "'" : "NULL") + "," + (this.ScheduledTime == null ? "NULL" : "'" + outputDateFormat.format(this.ScheduledTime) + "'") + "," + (this.EmailNotification == null ? "NULL" : "'" + this.EmailNotification + "'") + "," + (this.campusAdhocFilterID == 0 ? "NULL" : String.valueOf(this.campusAdhocFilterID)) + ")";
            this.pstmt = this.Backpack_Connection.prepareStatement(sql, 1);
            _irecordcount = this.pstmt.executeUpdate();
            if (_irecordcount > 0) {
               this.rs = this.pstmt.getGeneratedKeys();
               this.rs.next();
               this.Identifier = Blowfish.encrypt(String.valueOf(this.rs.getInt(1)), this.sCurrentDate);
               breturn = true;
            }
         }

         if (breturn && this.CustomReport != null && this.CustomReport.Identifier != null && !this.CustomReport.Identifier.isEmpty()) {
            Iterator var6 = this.CustomReport.parameters.iterator();

            while(var6.hasNext()) {
               Backpack_CustomReport.Parameter _param = (Backpack_CustomReport.Parameter)var6.next();
               sql = "UPDATE sp SET [scheduleparamvalue]=" + (_param.SelectedValue != null ? "'" + _param.SelectedValue + "'" : "p.DefaultValue") + " FROM [" + this.dbName + "]..schedule_parameter sp INNER JOIN [" + this.dbName + "]..[Param] p ON sp.paramID=p.paramID " + " WHERE sp.scheduleID=" + Blowfish.decrypt(this.Identifier, this.sCurrentDate) + " AND p.paramID=" + Blowfish.decrypt(_param.Identifier, this.sCurrentDate);
               this.pstmt = this.Backpack_Connection.prepareStatement(sql);
               _irecordcount = this.pstmt.executeUpdate();
               if (_irecordcount <= 0) {
                  sql = "INSERT INTO [" + this.dbName + "]..Schedule_Parameter (scheduleID,paramID,scheduleparamvalue) " + "SELECT " + Blowfish.decrypt(this.Identifier, this.sCurrentDate) + ",p.paramID," + (_param.SelectedValue != null ? "'" + _param.SelectedValue + "'" : "p.DefaultValue") + " FROM [" + this.dbName + "]..Param p WHERE p.paramID=" + Blowfish.decrypt(_param.Identifier, this.sCurrentDate);
                  this.pstmt = this.Backpack_Connection.prepareStatement(sql);
                  this.pstmt.executeUpdate();
               }
            }
         }

         if (this.ConfirmText != null && !this.ConfirmText.isEmpty()) {
            sql = "UPDATE [" + this.dbName + "]..CodeConfirm SET confirm_text='" + this.ConfirmText + "'," + "confirm_description=" + (this.ConfirmAcknowledgement != null && !this.ConfirmAcknowledgement.isEmpty() ? "'" + this.ConfirmAcknowledgement + "'" : "NULL") + " WHERE scheduleID IN (SELECT TOP 1 scheduleID FROM [" + this.dbName + "]..Schedule WHERE schedule_name='" + this.Name + "')";
            this.pstmt = this.Backpack_Connection.prepareStatement(sql);
            _irecordcount = this.pstmt.executeUpdate();
            if (_irecordcount == 0) {
               sql = "INSERT INTO [" + this.dbName + "]..CodeConfirm(scheduleID,confirm_text,confirm_description)VALUES((SELECT TOP 1 scheduleID FROM [" + this.dbName + "]..Schedule WHERE schedule_name='" + this.Name + "'),?,?)";
               this.pstmt = this.Backpack_Connection.prepareStatement(sql);
               this.pstmt.setString(1, this.ConfirmText);
               if (this.ConfirmText == null || this.ConfirmText.isEmpty()) {
                  this.pstmt.setNull(1, 12);
               }

               this.pstmt.setString(2, this.ConfirmAcknowledgement);
               if (this.ConfirmAcknowledgement == null || this.ConfirmAcknowledgement.isEmpty()) {
                  this.pstmt.setNull(2, 12);
               }

               this.pstmt.executeUpdate();
            }
         } else {
            sql = "DELETE [" + this.dbName + "]..CodeConfirm WHERE scheduleID IN (SELECT TOP 1 scheduleID FROM [" + this.dbName + "]..Schedule WHERE schedule_name='" + this.Name + "')";
            this.pstmt = this.Backpack_Connection.prepareStatement(sql);
            this.pstmt.executeUpdate();
         }
      } catch (Exception var15) {
         System.out.println(var15);
         breturn = false;
      } finally {
         try {
            this.close_connections();
         } catch (Exception var14) {
            System.out.println(var14);
         }

      }

      return breturn;
   }

   void close_connections() {
      try {
         if (this.rs != null) {
            this.rs.close();
         }
      } catch (Exception var3) {
      }

      try {
         if (this.pstmt != null) {
            this.pstmt.close();
         }
      } catch (Exception var2) {
      }

   }
   public boolean Delete() {
      boolean breturn = false;
      try {
          this.pstmt = this.Backpack_Connection.prepareStatement(
              "DELETE FROM [" + this.dbName + "]..Schedule WHERE scheduleID=?");
          this.pstmt.setInt(1, Integer.parseInt(Blowfish.decrypt(this.Identifier, this.sCurrentDate)));
          this.pstmt.executeUpdate();
          breturn = true;
      } catch (Exception ex) {
          System.out.println(ex);
          breturn = false;
      } finally {
          try {
              if (this.Backpack_Connection != null) {
                  this.Backpack_Connection.close();
              }
              if (this.pstmt != null) {
                  this.pstmt.close();
              }
          } catch (SQLException ex) {
              System.out.println(ex);
          }
      }
      return breturn;
  }
   public boolean Delete_DEP() {
      boolean breturn = false;

      try {
         this.pstmt = this.Backpack_Connection.prepareStatement("DELETE FROM [" + this.dbName + "]..Schedule WHERE scheduleID=" + Blowfish.decrypt(this.Identifier, this.sCurrentDate));
         this.pstmt.executeUpdate();
         breturn = true;
      } catch (Exception var11) {
         System.out.println(var11);
         breturn = false;
      } finally {
         try {
            if (this.Backpack_Connection != null) {
               this.Backpack_Connection.close();
            }

            if (this.pstmt != null) {
               this.pstmt.close();
            }
         } catch (SQLException var10) {
            System.out.println(var10);
         }

      }

      return breturn;
   }

   public void close() throws Exception {
      this.close_connections();
   }
}
