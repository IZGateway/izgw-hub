--
-- Table structure for table `accesscontrol`
--

DROP TABLE IF EXISTS `accesscontrol`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `accesscontrol` (
  `category` varchar(16) NOT NULL,
  `name` varchar(256) NOT NULL,
  `member` varchar(256) NOT NULL,
  `allow` tinyint DEFAULT NULL,
  PRIMARY KEY (`category`,`name`,`member`)
)/* ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci */;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `audit_history`
--

DROP TABLE IF EXISTS `audit_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_history` (
  `id` int NOT NULL AUTO_INCREMENT,
  `tableName` varchar(50) NOT NULL,
  `userName` varchar(50) NOT NULL,
  `changeType` enum('Insert','Update','Delete') NOT NULL,
  `oldValues` json DEFAULT NULL,
  `newValues` json DEFAULT NULL,
  `createdAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
)/* ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci */;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `certificatestatus`
--

DROP TABLE IF EXISTS `certificatestatus`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `certificatestatus` (
  `certificate_id` varchar(128) NOT NULL,
  `certificate_cn` varchar(128) DEFAULT NULL,
  `cert_serial_number` varchar(128) DEFAULT NULL,
  `last_checked_timestamp` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `next_check_timestamp` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_check_status` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`certificate_id`)
)/* ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci */;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `destination_type`
--

DROP TABLE IF EXISTS `destination_type`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `destination_type` (
  `type_id` int NOT NULL,
  `type` varchar(45) DEFAULT NULL,
  PRIMARY KEY (`type_id`)
)/* ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci */;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `jurisdiction`
--

DROP TABLE IF EXISTS `jurisdiction`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `jurisdiction` (
  `jurisdiction_id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(48) NOT NULL,
  `description` varchar(128) NOT NULL,
  `dest_prefix` varchar(10) DEFAULT NULL,
  PRIMARY KEY (`jurisdiction_id`)
)/* ENGINE=InnoDB AUTO_INCREMENT=64 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci*/;
/*!40101 SET character_set_client = @saved_cs_client */;


--
-- Table structure for table `destinations`
--

DROP TABLE IF EXISTS `destinations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `destinations` (
  `dest_id` varchar(128) NOT NULL,
  `dest_type` int NOT NULL,
  `dest_uri` varchar(1024) NOT NULL,
  `username` varchar(50) DEFAULT NULL,
  `password` varchar(256) DEFAULT NULL,
  `facility_id` varchar(50) DEFAULT NULL,
  `MSH3` varchar(50) DEFAULT NULL,
  `MSH4` varchar(50) DEFAULT NULL,
  `MSH5` varchar(50) DEFAULT NULL,
  `MSH6` varchar(50) DEFAULT NULL,
  `MSH22` varchar(50) DEFAULT NULL,
  `RXA11` varchar(50) DEFAULT NULL,
  `dest_version` varchar(50) DEFAULT NULL,
  `pass_expiry` date DEFAULT NULL,
  `jurisdiction_id` int NOT NULL,
  `maint_reason` varchar(256) DEFAULT NULL,
  `maint_start` datetime DEFAULT NULL,
  `maint_end` datetime DEFAULT NULL,
  PRIMARY KEY (`dest_id`,`dest_type`),
  CONSTRAINT `destinations_ibfk_1` FOREIGN KEY (`dest_type`) REFERENCES `destination_type` (`type_id`) ON DELETE RESTRICT,
  CONSTRAINT `destinations_ibfk_2` FOREIGN KEY (`jurisdiction_id`) REFERENCES `jurisdiction` (`jurisdiction_id`) ON DELETE RESTRICT ON UPDATE CASCADE
)/* ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci */;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `messageheaderinfo`
--

DROP TABLE IF EXISTS `messageheaderinfo`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `messageheaderinfo` (
  `msh` varchar(128) NOT NULL,
  `dest_id` varchar(128) DEFAULT NULL,
  `iis` varchar(128) DEFAULT NULL,
  `sourceType` varchar(128) DEFAULT NULL,
  `username` varchar(50) DEFAULT NULL,
  `password` varchar(256) DEFAULT NULL,
  `facility_id` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`msh`)
)/* ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci*/;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `destination_change_request`
--

DROP TABLE IF EXISTS `destination_change_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `destination_change_request` (
  `id` int NOT NULL AUTO_INCREMENT,
  `dest_uri` varchar(1024) DEFAULT NULL,
  `username` varchar(50) DEFAULT NULL,
  `password` varchar(50) DEFAULT NULL,
  `facility_id` varchar(50) DEFAULT NULL,
  `MSH3` varchar(50) DEFAULT NULL,
  `MSH4` varchar(50) DEFAULT NULL,
  `MSH5` varchar(50) DEFAULT NULL,
  `MSH6` varchar(50) DEFAULT NULL,
  `MSH22` varchar(50) DEFAULT NULL,
  `RXA11` varchar(50) DEFAULT NULL,
  `jira_id` varchar(50) DEFAULT NULL,
  `dest_id` varchar(128) DEFAULT NULL,
  `dest_type` int DEFAULT NULL,
  `scheduledAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `requestedAt` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `requestedBy` varchar(50) DEFAULT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `destination_change_request_ibfk_1` FOREIGN KEY (`dest_id`, `dest_type`) REFERENCES `destinations` (`dest_id`, `dest_type`)
)/* ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci */;
/*!40101 SET character_set_client = @saved_cs_client */;

